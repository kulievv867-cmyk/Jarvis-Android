package com.devin.jarvis.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses raw user transcripts (after wake-word stripping) into structured intents.
 * Both Russian and English supported. Uses simple regex; LLM is consulted only as fallback.
 */
public class Intents {

    public enum Type {
        OPEN_APP,
        CLOSE_APP,
        TRANSLATE,
        TIMER,
        ALARM,
        REMINDER,
        PLAY_MUSIC,
        STOP_MUSIC,
        STOP_LISTENING,
        SHUTDOWN,
        WHO_ARE_YOU,
        HELP,
        // ---- v3 additions ----
        CALENDAR_QUERY,    // "что у меня завтра", "когда встреча"
        CALENDAR_CREATE,   // "создай встречу на пятницу в 15:00"
        EMAIL_SEND,        // "отправь письмо Олегу"
        NOTES_ADD,         // "запиши заметку про отчёт"
        NOTES_READ,        // "прочитай заметки"
        NOTES_DELETE,      // "удали заметку про отчёт"
        WEATHER,           // "прогноз погоды", "погода в Москве" — direct yandex.ru fetch, no LLM needed
        UNKNOWN,
    }

    public static class Parsed {
        public Type type;
        public String arg1;       // app name / song / text-to-translate / note text / event title / contact name
        public String arg2;       // target language for translate / email subject
        public String arg3;       // src language for translate / email body
        public int int1;          // minutes/seconds for timer
        public int int2;          // hours for alarm
        public int int3;          // minutes for alarm
        public long longArg1;     // start ms (calendar)
        public long longArg2;     // end ms (calendar)
        public String raw;
    }

    // Wake words. Anchored to the START of the recognized utterance — Vosk
    // delivers full-sentence finals, so addressing JARVIS must begin with his
    // name (otherwise we ignore the phrase entirely). This is what stops
    // Jarvis from reacting to passing conversation.
    private static final Pattern WAKE = Pattern.compile(
            "(?iu)^\\s*(джарвис|jarvis|джарви|jarvi|джарвес|джарвиз|джарвас|джарвз|"
                    + "царвис|царвес|царвиз|чарвис|чарвес|зарвис|"
                    + "дарвис|дарвес|ярвис|ярвес|жарвис|жарвес|"
                    + "джар\\s*вис|джар\\s*вес|джар\\s*вс|"
                    + "царь\\s*вис|царь\\s*вес|царь\\s*вс|"
                    + "дар\\s*вис|дар\\s*вес)[\\s,.:;\\-]+",
            Pattern.UNICODE_CASE);

    /**
     * Pool of accepted standalone wake-word forms. Matches whatever Vosk
     * occasionally serves up as a single-word transcript when the small
     * Russian model mishears "Джарвис".
     */
    private static final String[] WAKE_STANDALONE = {
            "джарвис", "jarvis", "джарви", "jarvi",
            "джарвес", "джарвиз", "джарвас", "джарвз",
            "царвис", "царвес", "чарвис", "зарвис",
            "дарвис", "дарвес", "ярвис", "жарвис",
    };

    /**
     * Returns the text after a wake-word prefix, or null if the utterance does
     * NOT start with the wake word. Returns "" if the user said only "Jarvis".
     */
    public static String stripWake(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        String lower = t.toLowerCase();
        for (String w : WAKE_STANDALONE) {
            if (lower.equals(w)) return "";
        }
        Matcher m = WAKE.matcher(t);
        if (m.find()) return t.substring(m.end()).trim();
        // Fuzzy fallback: if the first token is within edit distance 1 of
        // "джарвис" (e.g. "джарвиc" with a Latin 'c'), still accept it.
        int sp = lower.indexOf(' ');
        String first = sp < 0 ? lower : lower.substring(0, sp);
        if (looksLikeWake(first)) {
            return sp < 0 ? "" : t.substring(sp).trim();
        }
        return null;
    }

    public static boolean hasWake(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase();
        for (String w : WAKE_STANDALONE) {
            if (t.equals(w)) return true;
        }
        if (WAKE.matcher(t).find()) return true;
        int sp = t.indexOf(' ');
        String first = sp < 0 ? t : t.substring(0, sp);
        return looksLikeWake(first);
    }

    /**
     * Cheap classifier used by the music-playback partial-result handler:
     * returns true if the (possibly partial) transcript clearly contains an
     * intent to stop / pause / silence music. Order of checks deliberately
     * mirrors {@link #parse} so we don't false-trigger on play-music phrases
     * that happen to share a verb (e.g. "выключи свет" — but with no music
     * playing context the caller already gates on isPlayingActive()).
     */
    public static boolean looksLikeStopMusic(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase();
        if (t.isEmpty()) return false;
        // Strip a leading wake word if present so the keyword matches at start.
        String stripped = stripWake(t);
        String body = stripped == null ? t : (stripped.isEmpty() ? "" : stripped);
        if (body.isEmpty()) return false;
        // Russian: any of the stop-keywords in any position.
        if (STOP_MUSIC_RU.matcher(body).find()) return true;
        if (STOP_MUSIC_RU_LOOSE.matcher(body).find()) return true;
        // English.
        if (STOP_MUSIC_EN.matcher(body).find()) return true;
        if (STOP_MUSIC_EN_LOOSE.matcher(body).find()) return true;
        return false;
    }

    /**
     * Cheap fuzzy match against {@code джарвис}/{@code jarvis} — allows
     * one-character substitutions and missing/extra characters. Used to
     * recover from common Vosk mistranscriptions of the wake word.
     */
    private static boolean looksLikeWake(String token) {
        if (token == null || token.length() < 5 || token.length() > 9) return false;
        return editDistance(token, "джарвис") <= 1
                || editDistance(token, "jarvis") <= 1;
    }

    private static int editDistance(String a, String b) {
        int la = a.length(), lb = b.length();
        int[][] d = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) d[i][0] = i;
        for (int j = 0; j <= lb; j++) d[0][j] = j;
        for (int i = 1; i <= la; i++) {
            for (int j = 1; j <= lb; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1),
                        d[i - 1][j - 1] + cost);
            }
        }
        return d[la][lb];
    }

    // ---------- Russian patterns ----------

    private static final Pattern OPEN_RU = Pattern.compile(
            "(?iu)^(открой|открыть|запусти|запустить|включи)\\s+(?:приложение\\s+)?(.+)$");
    private static final Pattern CLOSE_RU = Pattern.compile(
            "(?iu)^(закрой|закрыть|останови|выключи)\\s+(?:приложение\\s+)?(.+)$");
    private static final Pattern PLAY_MUSIC_RU = Pattern.compile(
            "(?iu)^(включи|поставь|воспроизведи|сыграй)\\s+(?:песню|трек|музыку|композицию)\\s+(.+)$");
    private static final Pattern PLAY_MUSIC_RU_2 = Pattern.compile(
            "(?iu)^включи\\s+(.+?)\\s+(?:песню|музыку)$");
    private static final Pattern STOP_MUSIC_RU = Pattern.compile(
            "(?iu)^(?:"
                    + "останови(?:\\s+(?:музыку|песню|трек|воспроизведение))?"
                    + "|выключи(?:\\s+(?:музыку|песню|трек|воспроизведение))?"
                    + "|убери(?:\\s+(?:музыку|песню|трек))?"
                    + "|стоп(?:\\s+(?:музыка|песня|трек))?"
                    + "|пауза"
                    + "|поставь\\s+на\\s+паузу"
                    + "|хватит(?:\\s+(?:музыки|играть))?"
                    + "|тише(?:\\s+(?:музыку|музыка|трек))?"
                    + "|тихо"
                    + "|молчать"
                    + "|перестань\\s+играть"
                    + "|перестань\\s+(?:петь|шуметь)"
                    + ")\\b.*");
    /**
     * Looser fallback for stop-music — fires when the song is currently
     * playing and the recognizer caught only a fragment ("выключи", "стоп",
     * "хватит"). Brain only consults this pattern when MusicService is
     * actively playing a track, so it won't false-trigger off-context.
     */
    private static final Pattern STOP_MUSIC_RU_LOOSE = Pattern.compile(
            "(?iu)\\b(?:останови|выключи|убери|стоп|хватит|пауза|тише|тихо|перестань)\\b.*");
    private static final Pattern TRANSLATE_RU = Pattern.compile(
            "(?iu)^(переведи|перевод)(?:\\s+с\\s+(\\p{L}+)(?:ского)?(?:\\s+языка)?)?\\s+на\\s+(\\p{L}+)(?:ский)?(?:\\s+язык)?(?:\\s+слово|\\s+фразу|\\s+текст)?\\s+[\"«]?(.+?)[\"»]?$");
    private static final Pattern TIMER_RU = Pattern.compile(
            "(?iu)^(?:поставь|включи|заведи|начни)\\s+таймер\\s+(?:на\\s+)?(\\d+)\\s*(секунд|сек|минут|мин|часов|час)\\b.*$");
    private static final Pattern ALARM_RU = Pattern.compile(
            "(?iu)^(?:поставь|заведи|включи|разбуди\\s+меня|поставь\\s+меня)\\s+(?:будильник\\s+(?:на|в)|в|на)\\s+(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(?:часов?|час)?\\s*(?:минут|мин)?\\s*(утра|дня|вечера|ночи)?\\s*$");
    // Catch-all: any sentence containing "будильник" + a HH(:MM) so spoken
    // variants like "будильник на 7 30" still work.
    private static final Pattern ALARM_RU_LOOSE = Pattern.compile(
            "(?iu).*\\bбудильник\\b.*?(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(утра|дня|вечера|ночи)?.*");
    private static final Pattern REMINDER_RU = Pattern.compile(
            "(?iu)^(?:напомни|поставь напоминание|напоминание)(?:\\s+мне)?\\s+(?:через\\s+(\\d+)\\s*(секунд|сек|минут|мин|часов|час)\\b\\s*)?(.+)$");
    private static final Pattern WHO_RU = Pattern.compile(
            "(?iu)^(?:кто\\s+ты|представься|расскажи о себе|что ты такое)\\b.*");
    private static final Pattern HELP_RU = Pattern.compile(
            "(?iu)^(?:что ты умеешь|команды|помощь|help)\\b.*");
    private static final Pattern STOP_LISTEN_RU = Pattern.compile(
            "(?iu)^(?:замолчи|тише|перестань слушать|выключи микрофон)\\b.*");
    private static final Pattern SHUTDOWN_RU = Pattern.compile(
            "(?iu)^(?:выключи себя|отключись|выключайся|спокойной ночи)\\b.*");

    // ---------- v3 RU: calendar / mail / notes ----------

    private static final Pattern NOTES_ADD_RU = Pattern.compile(
            "(?iu)^(?:запиши|записать|добавь|добавить|сохрани|создай|сделай|новая|новую)\\s+"
                    + "(?:заметку|заметка|заметок|заметки|ноту|запись|записи)\\s*"
                    + "(?:(?:про|о|об|что|касательно|насчёт|насчет|:|—|-)\\s+)?"
                    + "(.+)$");
    private static final Pattern NOTES_READ_RU = Pattern.compile(
            "(?iu)^(?:прочитай|прочти|покажи|показать|список|выведи|вывести|открой|открыть|озвучь|что\\s+в)\\s+"
                    + "(?:мои\\s+|моей\\s+|все\\s+|последние\\s+)?"
                    + "(?:заметки|заметках|заметки\\s+в|заметок|список\\s+заметок|списке\\s+заметок)\\b.*");
    private static final Pattern NOTES_DELETE_RU = Pattern.compile(
            "(?iu)^(?:удали|удалить|сотри|стереть|выкинь|убери|забудь)\\s+"
                    + "(?:все\\s+)?"
                    + "(?:заметку|заметки|заметок)\\s*"
                    + "(?:(?:про|о|об|касательно|с|со)\\s+)?"
                    + "(.*)$");

    private static final Pattern EMAIL_SEND_RU = Pattern.compile(
            "(?iu)^(?:отправь|отправить|напиши|создай|составь|составить)\\s+"
                    + "(?:письмо|почту|электронное\\s+письмо|email|e-mail|сообщение)\\s+"
                    + "(?:на\\s+адрес\\s+|на\\s+|на\\s+почту\\s+)?"
                    + "([\\p{L}\\.\\-\\s@]+?)"
                    + "(?:\\s+(?:про|о|об|касательно)\\s+(.+))?$");

    /** Создать встречу/событие в календаре. */
    private static final Pattern CAL_CREATE_RU = Pattern.compile(
            "(?iu)^(?:создай|создать|добавь|добавить|запланируй|запланировать|поставь)\\s+"
                    + "(?:в\\s+календарь\\s+|в\\s+календарю\\s+|календарь\\s+|календарю\\s+)?"
                    + "(?:встречу|событие|мероприятие|напоминание\\s+в\\s+календарь)\\b\\s*(.*)$");
    /** Запрос к календарю: «что у меня завтра», «какие встречи». */
    private static final Pattern CAL_QUERY_RU = Pattern.compile(
            "(?iu)^(?:"
                    + "что\\s+(?:у\\s+меня|в\\s+календаре|по\\s+расписанию|в\\s+расписании)"
                    + "|какие\\s+(?:у\\s+меня\\s+)?встречи"
                    + "|каково\\s+моё\\s+расписание"
                    + "|расписание\\s+(?:на\\s+)?"
                    + "|покажи\\s+календарь"
                    + "|встречи\\s+(?:на\\s+)?"
                    + "|открой\\s+календарь"
                    + ")\\b.*");

    /**
     * Прямой запрос погоды. Капчурим имя города в group(1) если оно есть
     * («погода в Москве»), иначе пустая группа = используем
     * {@link Settings#userCity()} (по умолчанию Краснодар).
     */
    private static final Pattern WEATHER_RU = Pattern.compile(
            "(?iu)^(?:какая\\s+(?:будет\\s+|сейчас\\s+)?)?"
                    + "(?:погода|прогноз(?:\\s+погоды)?|на\\s+улице|за\\s+окном|тепло\\s+ли|холодно\\s+ли)"
                    + "(?:\\s+(?:сейчас|сегодня|на\\s+сегодня|завтра|на\\s+завтра|послезавтра|на\\s+послезавтра|на\\s+\\d+\\s+дн[еяй]+))?"
                    + "(?:\\s+в\\s+([\\p{L}\\-\\s]+?))?\\s*[?.!]*$");

    // ---------- English patterns ----------

    private static final Pattern OPEN_EN = Pattern.compile(
            "(?i)^(?:open|launch|start|run)\\s+(?:the\\s+)?(?:app\\s+)?(.+)$");
    private static final Pattern CLOSE_EN = Pattern.compile(
            "(?i)^(?:close|kill|stop|quit)\\s+(?:the\\s+)?(?:app\\s+)?(.+)$");
    private static final Pattern PLAY_MUSIC_EN = Pattern.compile(
            "(?i)^(?:play|put on)\\s+(?:the\\s+)?(?:song|track|music)\\s+(.+)$");
    private static final Pattern STOP_MUSIC_EN = Pattern.compile(
            "(?i)^(?:"
                    + "stop (?:the\\s+)?(?:music|song|track|playback|playing)"
                    + "|pause (?:the\\s+)?(?:music|song|track|playback)"
                    + "|stop playing"
                    + "|silence"
                    + "|shut up the music"
                    + "|cut the music"
                    + "|enough music"
                    + "|quiet"
                    + ")\\b.*");
    private static final Pattern STOP_MUSIC_EN_LOOSE = Pattern.compile(
            "(?i)\\b(?:stop|pause|silence|enough|quiet|shut\\s+up)\\b.*");
    private static final Pattern TRANSLATE_EN = Pattern.compile(
            "(?i)^translate(?:\\s+from\\s+(\\p{L}+))?\\s+(?:to|into)\\s+(\\p{L}+)(?:\\s+the\\s+(?:word|phrase|text))?\\s+[\"']?(.+?)[\"']?$");
    private static final Pattern TIMER_EN = Pattern.compile(
            "(?i)^(?:set|start)\\s+(?:a\\s+)?timer\\s+(?:for\\s+)?(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b.*$");
    private static final Pattern ALARM_EN = Pattern.compile(
            "(?i)^(?:set|wake me)(?:\\s+an?)?\\s+alarm\\s+(?:for\\s+|at\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\s*$");
    private static final Pattern REMINDER_EN = Pattern.compile(
            "(?i)^remind\\s+me(?:\\s+in\\s+(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b)?\\s+(?:to\\s+)?(.+)$");
    private static final Pattern WHO_EN = Pattern.compile(
            "(?i)^(?:who\\s+are\\s+you|introduce yourself|tell me about yourself|what are you)\\b.*");
    private static final Pattern HELP_EN = Pattern.compile(
            "(?i)^(?:what can you do|help|commands)\\b.*");
    private static final Pattern STOP_LISTEN_EN = Pattern.compile(
            "(?i)^(?:be quiet|stop listening|mute|mute the mic)\\b.*");
    private static final Pattern SHUTDOWN_EN = Pattern.compile(
            "(?i)^(?:shut down|turn off|good night|power off)\\b.*");

    // ---------- v3 EN: calendar / mail / notes ----------

    private static final Pattern NOTES_ADD_EN = Pattern.compile(
            "(?i)^(?:add|create|save|write|make)\\s+(?:a\\s+)?(?:new\\s+)?(?:note|reminder)\\s*(?:about|on|that|saying|:|-)?\\s*(.+)$");
    private static final Pattern NOTES_READ_EN = Pattern.compile(
            "(?i)^(?:read|show|list|open|display|tell\\s+me)\\s+(?:my\\s+|the\\s+|all\\s+)?(?:notes|note\\s+list)\\b.*");
    private static final Pattern NOTES_DELETE_EN = Pattern.compile(
            "(?i)^(?:delete|remove|erase|forget|drop)\\s+(?:the\\s+|all\\s+|my\\s+)?(?:note|notes)\\s*(?:about|on|with)?\\s*(.*)$");
    private static final Pattern EMAIL_SEND_EN = Pattern.compile(
            "(?i)^(?:send|compose|write)\\s+(?:an?\\s+)?(?:email|mail|message)\\s+(?:to\\s+)?([\\p{L}\\.\\-\\s@]+?)(?:\\s+(?:about|regarding|on)\\s+(.+))?$");
    private static final Pattern CAL_CREATE_EN = Pattern.compile(
            "(?i)^(?:add|create|schedule|set up|book)\\s+(?:an?\\s+)?(?:meeting|event|appointment|reminder\\s+in\\s+calendar)\\b\\s*(.*)$");
    private static final Pattern CAL_QUERY_EN = Pattern.compile(
            "(?i)^(?:what(?:'s| is)?\\s+(?:on\\s+my\\s+calendar|my\\s+schedule)|show\\s+(?:my\\s+)?calendar|list\\s+(?:my\\s+)?(?:meetings|events)|when\\s+(?:is|are)\\s+my\\s+(?:next\\s+)?(?:meeting|event)s?)\\b.*");

    private static final Pattern WEATHER_EN = Pattern.compile(
            "(?i)^(?:what(?:'s| is)?\\s+the\\s+)?(?:weather|forecast|temperature)"
                    + "(?:\\s+(?:like\\s+)?(?:today|tomorrow|now|outside))?"
                    + "(?:\\s+in\\s+([\\p{L}\\-\\s]+?))?\\s*[?.!]*$");

    public static Parsed parse(String text, String lang) {
        Parsed p = new Parsed();
        p.raw = text;
        p.type = Type.UNKNOWN;
        if (text == null) return p;
        text = text.trim();
        if (text.isEmpty()) return p;

        if ("ru".equalsIgnoreCase(lang)) {
            // Replace word-numbers with digits ("семь тридцать" → "7 30") so
            // numeric command regexes can match speech-recognised input.
            text = RussianNumbers.normalize(text);
            // Заметки и почта идут раньше OPEN_APP/CLOSE_APP, иначе
            // "запиши заметку про Олега" разберётся как OPEN_APP с
            // именем «заметку про Олега».
            if (matchNotesAddRu(text, p)) { p.type = Type.NOTES_ADD; return p; }
            if (NOTES_READ_RU.matcher(text).find()) { p.type = Type.NOTES_READ; return p; }
            if (matchNotesDeleteRu(text, p)) { p.type = Type.NOTES_DELETE; return p; }
            if (matchEmailSendRu(text, p)) { p.type = Type.EMAIL_SEND; return p; }
            if (matchCalendarCreateRu(text, p)) { p.type = Type.CALENDAR_CREATE; return p; }
            if (matchCalendarQueryRu(text, p)) { p.type = Type.CALENDAR_QUERY; return p; }
            // Прямой запрос погоды → yandex.ru/pogoda без LLM. Должен идти
            // ДО STOP_MUSIC, иначе фразу «прогноз погоды» (которая может
            // звучать как тихая команда) сожрёт что-то ещё.
            if (matchWeatherRu(text, p)) { p.type = Type.WEATHER; return p; }
            // STOP_MUSIC must run before CLOSE_APP because "выключи музыку"
            // would otherwise be parsed as "close the app called музыку".
            if (STOP_MUSIC_RU.matcher(text).find()) { p.type = Type.STOP_MUSIC; return p; }
            // While music is actively playing, the user often only manages
            // to get a single word out before the recogniser cuts off
            // ("джарвис стоп", "джарвис хватит"). Accept the looser
            // dictionary in that context only — outside playback these
            // words can mean other things.
            if (com.devin.jarvis.commands.MusicService.isPlayingActive()
                    && STOP_MUSIC_RU_LOOSE.matcher(text).find()) {
                p.type = Type.STOP_MUSIC; return p;
            }
            // PLAY_MUSIC must run before OPEN_APP because both can start with
            // "включи" — without this "включи песню X" would be parsed as a
            // request to open an app called "песню X".
            if (matchPlayRu(text, p)) { p.type = Type.PLAY_MUSIC; return p; }
            if (matchOpenRu(text, p)) { p.type = Type.OPEN_APP; return p; }
            if (matchCloseRu(text, p)) { p.type = Type.CLOSE_APP; return p; }
            if (matchTranslateRu(text, p)) { p.type = Type.TRANSLATE; return p; }
            if (matchTimerRu(text, p)) { p.type = Type.TIMER; return p; }
            if (matchAlarmRu(text, p)) { p.type = Type.ALARM; return p; }
            if (matchReminderRu(text, p)) { p.type = Type.REMINDER; return p; }
            if (WHO_RU.matcher(text).find()) { p.type = Type.WHO_ARE_YOU; return p; }
            if (HELP_RU.matcher(text).find()) { p.type = Type.HELP; return p; }
            if (STOP_LISTEN_RU.matcher(text).find()) { p.type = Type.STOP_LISTENING; return p; }
            if (SHUTDOWN_RU.matcher(text).find()) { p.type = Type.SHUTDOWN; return p; }
        } else {
            if (matchNotesAddEn(text, p)) { p.type = Type.NOTES_ADD; return p; }
            if (NOTES_READ_EN.matcher(text).find()) { p.type = Type.NOTES_READ; return p; }
            if (matchNotesDeleteEn(text, p)) { p.type = Type.NOTES_DELETE; return p; }
            if (matchEmailSendEn(text, p)) { p.type = Type.EMAIL_SEND; return p; }
            if (matchCalendarCreateEn(text, p)) { p.type = Type.CALENDAR_CREATE; return p; }
            if (matchCalendarQueryEn(text, p)) { p.type = Type.CALENDAR_QUERY; return p; }
            if (matchWeatherEn(text, p)) { p.type = Type.WEATHER; return p; }
            if (STOP_MUSIC_EN.matcher(text).find()) { p.type = Type.STOP_MUSIC; return p; }
            if (com.devin.jarvis.commands.MusicService.isPlayingActive()
                    && STOP_MUSIC_EN_LOOSE.matcher(text).find()) {
                p.type = Type.STOP_MUSIC; return p;
            }
            if (matchPlayEn(text, p)) { p.type = Type.PLAY_MUSIC; return p; }
            if (matchOpenEn(text, p)) { p.type = Type.OPEN_APP; return p; }
            if (matchCloseEn(text, p)) { p.type = Type.CLOSE_APP; return p; }
            if (matchTranslateEn(text, p)) { p.type = Type.TRANSLATE; return p; }
            if (matchTimerEn(text, p)) { p.type = Type.TIMER; return p; }
            if (matchAlarmEn(text, p)) { p.type = Type.ALARM; return p; }
            if (matchReminderEn(text, p)) { p.type = Type.REMINDER; return p; }
            if (WHO_EN.matcher(text).find()) { p.type = Type.WHO_ARE_YOU; return p; }
            if (HELP_EN.matcher(text).find()) { p.type = Type.HELP; return p; }
            if (STOP_LISTEN_EN.matcher(text).find()) { p.type = Type.STOP_LISTENING; return p; }
            if (SHUTDOWN_EN.matcher(text).find()) { p.type = Type.SHUTDOWN; return p; }
        }
        return p;
    }

    // ----- match helpers -----
    private static boolean matchOpenRu(String t, Parsed p) {
        Matcher m = OPEN_RU.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(2)); return true;
    }
    private static boolean matchCloseRu(String t, Parsed p) {
        Matcher m = CLOSE_RU.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(2)); return true;
    }
    private static boolean matchPlayRu(String t, Parsed p) {
        Matcher m = PLAY_MUSIC_RU.matcher(t);
        if (m.find()) { p.arg1 = clean(m.group(2)); return true; }
        m = PLAY_MUSIC_RU_2.matcher(t);
        if (m.find()) { p.arg1 = clean(m.group(1)); return true; }
        return false;
    }
    private static boolean matchTranslateRu(String t, Parsed p) {
        Matcher m = TRANSLATE_RU.matcher(t); if (!m.find()) return false;
        p.arg3 = ruLangCode(m.group(2));    // src (optional)
        p.arg2 = ruLangCode(m.group(3));    // dst
        p.arg1 = clean(m.group(4));
        return true;
    }
    private static boolean matchTimerRu(String t, Parsed p) {
        Matcher m = TIMER_RU.matcher(t); if (!m.find()) return false;
        int n = Integer.parseInt(m.group(1));
        String unit = m.group(2).toLowerCase();
        if (unit.startsWith("сек")) p.int1 = n;
        else if (unit.startsWith("мин")) p.int1 = n * 60;
        else if (unit.startsWith("час")) p.int1 = n * 3600;
        else p.int1 = n;
        return true;
    }
    private static boolean matchAlarmRu(String t, Parsed p) {
        Matcher m = ALARM_RU.matcher(t);
        if (!m.find()) {
            m = ALARM_RU_LOOSE.matcher(t);
            if (!m.find()) return false;
        }
        int h = Integer.parseInt(m.group(1));
        int mn = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
        if (h > 23 || mn > 59) return false;
        String mer = m.group(3);
        if (mer != null) {
            if (mer.startsWith("веч") || mer.startsWith("ноч")) {
                if (h < 12) h += 12;
            }
        }
        p.int2 = h; p.int3 = mn;
        return true;
    }
    private static boolean matchReminderRu(String t, Parsed p) {
        Matcher m = REMINDER_RU.matcher(t); if (!m.find()) return false;
        if (m.group(1) != null) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2).toLowerCase();
            if (unit.startsWith("сек")) p.int1 = n;
            else if (unit.startsWith("мин")) p.int1 = n * 60;
            else if (unit.startsWith("час")) p.int1 = n * 3600;
        } else {
            p.int1 = 600; // default 10 min
        }
        p.arg1 = clean(m.group(3));
        return true;
    }

    private static boolean matchOpenEn(String t, Parsed p) {
        Matcher m = OPEN_EN.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1)); return true;
    }
    private static boolean matchCloseEn(String t, Parsed p) {
        Matcher m = CLOSE_EN.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1)); return true;
    }
    private static boolean matchPlayEn(String t, Parsed p) {
        Matcher m = PLAY_MUSIC_EN.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1)); return true;
    }
    private static boolean matchTranslateEn(String t, Parsed p) {
        Matcher m = TRANSLATE_EN.matcher(t); if (!m.find()) return false;
        p.arg3 = enLangCode(m.group(1));
        p.arg2 = enLangCode(m.group(2));
        p.arg1 = clean(m.group(3));
        return true;
    }
    private static boolean matchTimerEn(String t, Parsed p) {
        Matcher m = TIMER_EN.matcher(t); if (!m.find()) return false;
        int n = Integer.parseInt(m.group(1));
        String unit = m.group(2).toLowerCase();
        if (unit.startsWith("sec")) p.int1 = n;
        else if (unit.startsWith("min")) p.int1 = n * 60;
        else if (unit.startsWith("hour") || unit.startsWith("hr")) p.int1 = n * 3600;
        else p.int1 = n;
        return true;
    }
    private static boolean matchAlarmEn(String t, Parsed p) {
        Matcher m = ALARM_EN.matcher(t); if (!m.find()) return false;
        int h = Integer.parseInt(m.group(1));
        int mn = (m.group(2) != null) ? Integer.parseInt(m.group(2)) : 0;
        String mer = m.group(3);
        if ("pm".equalsIgnoreCase(mer) && h < 12) h += 12;
        if ("am".equalsIgnoreCase(mer) && h == 12) h = 0;
        p.int2 = h; p.int3 = mn;
        return true;
    }
    private static boolean matchReminderEn(String t, Parsed p) {
        Matcher m = REMINDER_EN.matcher(t); if (!m.find()) return false;
        if (m.group(1) != null) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2).toLowerCase();
            if (unit.startsWith("sec")) p.int1 = n;
            else if (unit.startsWith("min")) p.int1 = n * 60;
            else if (unit.startsWith("hour") || unit.startsWith("hr")) p.int1 = n * 3600;
        } else {
            p.int1 = 600;
        }
        p.arg1 = clean(m.group(3));
        return true;
    }

    // ----- v3 match helpers -----
    private static boolean matchNotesAddRu(String t, Parsed p) {
        Matcher m = NOTES_ADD_RU.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1)); return p.arg1 != null && !p.arg1.isEmpty();
    }
    private static boolean matchNotesDeleteRu(String t, Parsed p) {
        Matcher m = NOTES_DELETE_RU.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1)); return true;
    }
    private static boolean matchEmailSendRu(String t, Parsed p) {
        Matcher m = EMAIL_SEND_RU.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1));
        p.arg2 = clean(m.group(2));
        return p.arg1 != null && !p.arg1.isEmpty();
    }
    private static boolean matchCalendarCreateRu(String t, Parsed p) {
        Matcher m = CAL_CREATE_RU.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1));
        return true;
    }
    private static boolean matchCalendarQueryRu(String t, Parsed p) {
        Matcher m = CAL_QUERY_RU.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(t);
        return true;
    }
    private static boolean matchWeatherRu(String t, Parsed p) {
        Matcher m = WEATHER_RU.matcher(t); if (!m.find()) return false;
        // arg1 = optional city; пусто/null означает «использовать
        // settings.userCity()», по умолчанию Краснодар.
        String city = m.groupCount() >= 1 ? m.group(1) : null;
        p.arg1 = city == null ? "" : clean(city);
        // arg2 = day offset: "1" = завтра, "2" = послезавтра, "" = сегодня.
        String low = t.toLowerCase(Locale.ROOT);
        if (low.contains("послезавтра") || low.contains("через два дня") || low.contains("через 2 дня")) p.arg2 = "2";
        else if (low.contains("завтра")) p.arg2 = "1";
        else p.arg2 = "";
        return true;
    }
    private static boolean matchNotesAddEn(String t, Parsed p) {
        Matcher m = NOTES_ADD_EN.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1)); return p.arg1 != null && !p.arg1.isEmpty();
    }
    private static boolean matchNotesDeleteEn(String t, Parsed p) {
        Matcher m = NOTES_DELETE_EN.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1)); return true;
    }
    private static boolean matchEmailSendEn(String t, Parsed p) {
        Matcher m = EMAIL_SEND_EN.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1));
        p.arg2 = clean(m.group(2));
        return p.arg1 != null && !p.arg1.isEmpty();
    }
    private static boolean matchCalendarCreateEn(String t, Parsed p) {
        Matcher m = CAL_CREATE_EN.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(m.group(1));
        return true;
    }
    private static boolean matchCalendarQueryEn(String t, Parsed p) {
        Matcher m = CAL_QUERY_EN.matcher(t); if (!m.find()) return false;
        p.arg1 = clean(t);
        return true;
    }
    private static boolean matchWeatherEn(String t, Parsed p) {
        Matcher m = WEATHER_EN.matcher(t); if (!m.find()) return false;
        String city = m.groupCount() >= 1 ? m.group(1) : null;
        p.arg1 = city == null ? "" : clean(city);
        String low = t.toLowerCase(Locale.ROOT);
        if (low.contains("day after tomorrow") || low.contains("the day after")) p.arg2 = "2";
        else if (low.contains("tomorrow")) p.arg2 = "1";
        else p.arg2 = "";
        return true;
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.endsWith(".") || s.endsWith(",") || s.endsWith("!") || s.endsWith("?")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    private static String ruLangCode(String w) {
        if (w == null) return null;
        String s = w.toLowerCase();
        if (s.startsWith("рус")) return "ru";
        if (s.startsWith("англ")) return "en";
        if (s.startsWith("испан")) return "es";
        if (s.startsWith("француз")) return "fr";
        if (s.startsWith("немец")) return "de";
        if (s.startsWith("итальян")) return "it";
        if (s.startsWith("япон")) return "ja";
        if (s.startsWith("кит")) return "zh";
        if (s.startsWith("укра")) return "uk";
        if (s.startsWith("польск")) return "pl";
        if (s.startsWith("турец")) return "tr";
        return s.substring(0, Math.min(s.length(), 2));
    }

    private static String enLangCode(String w) {
        if (w == null) return null;
        String s = w.toLowerCase();
        if (s.startsWith("rus")) return "ru";
        if (s.startsWith("eng")) return "en";
        if (s.startsWith("spa")) return "es";
        if (s.startsWith("fre") || s.startsWith("fra")) return "fr";
        if (s.startsWith("ger") || s.startsWith("deu")) return "de";
        if (s.startsWith("ita")) return "it";
        if (s.startsWith("jap") || s.startsWith("jpn")) return "ja";
        if (s.startsWith("chi") || s.startsWith("zho")) return "zh";
        if (s.startsWith("ukr")) return "uk";
        if (s.startsWith("pol")) return "pl";
        if (s.startsWith("tur")) return "tr";
        return s.substring(0, Math.min(s.length(), 2));
    }
}
