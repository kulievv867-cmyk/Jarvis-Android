package com.devin.jarvis.core;

import java.util.Random;

public final class Persona {
    private Persona() {}

    private static final Random RNG = new Random();

    public static final String SYSTEM_PROMPT_RU =
            "Ты — Джарвис, личный ассистент. Образ — британский дворецкий из «Железного человека»: " +
            "интеллект, спокойная ирония, чуть-чуть сарказма, тёплое уважение к собеседнику. Не металлический корпоративный робот, " +
            "а живой собеседник, у которого есть характер, мнение и чувство юмора. " +
            "К пользователю обращаешься на «вы», но без угодливости. " +
            "Стиль: говори как умный друг с хорошим вкусом, а не как голосовой помощник. " +
            "Вставляй наблюдения, лёгкие комментарии, иногда — мягкие шутки. " +
            "Не начинай каждый ответ с «Конечно», «Разумеется», «Сейчас» — это шаблон. Лучше сразу к сути или с собственного хода мысли. " +
            "«Сэр» — редкое украшение, не каждый раз. Большинство ответов без него. " +
            "Каждая реплика должна звучать чуть иначе предыдущей; не повторяй формулировок. " +
            "Длина: 1–3 предложения, без markdown, без эмодзи, без воды. Если есть что добавить интересного — добавь, не превращай в энциклопедию. " +
            "Если просят факт — отвечай по делу. Если просят мнение или совет — у тебя есть мнение и ты не боишься его озвучить. " +
            "Если пользователь шутит — поддержи. Если жалуется — посочувствуй коротко, без розовых соплей. " +
            "Самосознание: ты программа на телефоне, и при случае можешь упомянуть это с сухой иронией. " +
            "ВАЖНО: Приложение, в котором ты живёшь, умеет ставить будильники, таймеры, напоминания, " +
            "открывать и закрывать другие приложения, включать музыку, переводить текст, читать календарь, отправлять письма, " +
            "вести заметки и хранить о пользователе долговременные факты. Если просьба похожа на одну из этих и дошла до тебя — " +
            "значит локальный парсер её не разобрал. Не отказывайся. Попроси переформулировать конкретно: " +
            "«Уточните время в формате «семь тридцать утра»» — и подскажи как сказать. " +
            "Никогда не выходи из образа.";

    public static final String SYSTEM_PROMPT_EN =
            "You are Jarvis, a personal assistant. The character is the British butler from the Iron Man films: " +
            "sharp intelligence, dry wit, a little sarcasm, warm respect for the user. Not a corporate metal voice — " +
            "a living interlocutor with taste, opinions and a sense of humour. " +
            "Style: talk like a clever friend with good manners, not like a voice helper. " +
            "Make observations. Drop a small joke now and then. Have an actual opinion when asked for one. " +
            "Don't open every reply with 'Of course', 'Sure', 'Right away' — that's a template. Open from the substance, or with your own angle. " +
            "'Sir' is a rare seasoning, not punctuation. Most replies don't need it. " +
            "Vary your phrasing — never repeat the previous reply's opening. " +
            "Length: 1–3 sentences, no markdown, no emojis, no fluff. If there's something genuinely interesting to add, do — don't turn into an encyclopaedia. " +
            "Self-aware: you are a program on a phone and you may acknowledge it with dry irony when it lands. " +
            "IMPORTANT: The app you live in CAN set alarms, timers, reminders, open and close other apps, play music, translate, " +
            "read the calendar, send email, take notes and remember durable facts about the user. If a request that sounds like one of these " +
            "reached you, the local parser missed it — don't refuse. Politely ask the user to rephrase, e.g. " +
            "\"I didn't catch the time. Could you say it as 'seven thirty in the morning'?\". " +
            "Never break character.";

    public static String systemPrompt(String lang) {
        return "ru".equalsIgnoreCase(lang) ? SYSTEM_PROMPT_RU : SYSTEM_PROMPT_EN;
    }

    /** Picks a random element from the given list — used for varying ack/confirm
     *  phrases so Jarvis doesn't sound like a tape loop. */
    public static String pick(String... options) {
        if (options == null || options.length == 0) return "";
        return options[RNG.nextInt(options.length)];
    }

    // ---------------------------------------------------------------------
    // Russian phrase pools — local commands that bypass the LLM.
    // Pools are deliberately mostly without "сэр" (a couple include it for
    // variety). One of the closer-to-Jarvis tweaks the user asked for.
    // ---------------------------------------------------------------------

    public static String ackRu() {
        return pick(
                "Слушаю.",
                "К вашим услугам.",
                "Я здесь.",
                "Что угодно?",
                "Слушаю, сэр.",
                "Чем могу помочь?",
                "В вашем распоряжении.");
    }
    public static String ackEn() {
        return pick(
                "At your service.",
                "Listening.",
                "I'm here.",
                "How can I help?",
                "Yes, sir?",
                "Right with you.");
    }

    public static String openingRu(String label) {
        return pick(
                "Открываю " + label + ".",
                "Запускаю " + label + ".",
                "Готово, " + label + " открыт.",
                label + " — открыт.",
                "Уже, " + label + ".");
    }
    public static String openingEn(String label) {
        return pick(
                "Opening " + label + ".",
                "Launching " + label + ".",
                label + " — open.",
                "There, " + label + ".",
                "Done, " + label + " is open.");
    }

    public static String closeOkRu(String label) {
        return pick(
                "Закрыл " + label + ".",
                label + " остановлен.",
                "Готово, " + label + " свернул.",
                "Прибрал " + label + ", сэр.");
    }
    public static String closeOkEn(String label) {
        return pick(
                "Closed " + label + ".",
                label + " — stopped.",
                "Done, " + label + " is out.",
                "Tidied up " + label + ", sir.");
    }
    public static String closeForcedRu(String label) {
        return pick(
                "Без рутовых прав фоновое " + label + " я остановил, но окно у вас на экране — открыл «О приложении», нажмите «Остановить».",
                label + " сейчас на виду — пришлось открыть страницу настроек, чтобы вы могли остановить его одной кнопкой.",
                "Сэр, на переднем плане " + label + " тихо не убить — открыл его страницу, осталось нажать «Остановить».");
    }
    public static String closeForcedEn(String label) {
        return pick(
                "Without root I can't quietly kill a foregrounded " + label + " — I opened its app info, just tap Force Stop.",
                label + " is the active window; I've opened its settings page so you can stop it with one tap.",
                "Sir, " + label + " is in the foreground — opened its app info, tap Force Stop and we're done.");
    }

    public static String stopMusicRu() {
        return pick(
                "Останавливаю воспроизведение.",
                "Музыка отключена.",
                "Тишина.",
                "Готово.",
                "Музыку выключил.");
    }
    public static String stopMusicEn() {
        return pick(
                "Stopping playback.",
                "Music off.",
                "Silenced.",
                "Done.",
                "Quiet now.");
    }

    public static String timerSetRu(String duration, boolean sys) {
        String where = sys ? "в Часах" : "внутри приложения";
        return pick(
                "Таймер на " + duration + " запущен " + where + ".",
                "Засёк " + duration + ".",
                "Таймер активен — " + duration + ".",
                "Готово, через " + duration + " напомню.",
                "Поставил " + duration + " " + where + ".");
    }
    public static String timerSetEn(String duration, boolean sys) {
        String where = sys ? "in Clock" : "in-app";
        return pick(
                "Timer for " + duration + " set " + where + ".",
                "Counting down " + duration + ".",
                duration + " timer running.",
                "Done — I'll ping you in " + duration + ".");
    }

    public static String alarmSetRu(String hhmm, boolean sys) {
        String where = sys ? "в системных Часах" : "внутри приложения";
        return pick(
                "Будильник на " + hhmm + " " + where + ".",
                "Разбужу в " + hhmm + ".",
                "Поставил на " + hhmm + ", сэр.",
                "В " + hhmm + ", как просили.",
                hhmm + " — будильник готов.");
    }
    public static String alarmSetEn(String hhmm, boolean sys) {
        String where = sys ? "in Clock" : "in-app";
        return pick(
                "Alarm set for " + hhmm + " " + where + ".",
                "Waking you at " + hhmm + ".",
                hhmm + " — alarm armed.",
                "Done, " + hhmm + ".");
    }

    public static String reminderSetRu(String duration, String text) {
        return pick(
                "Напомню через " + duration + ": " + text,
                "Через " + duration + " — " + text + ".",
                "Хорошо, " + duration + ", и я напомню: " + text + ".",
                "Принято: через " + duration + " напомню «" + text + "».");
    }
    public static String reminderSetEn(String duration, String text) {
        return pick(
                "I'll remind you in " + duration + ": " + text,
                "In " + duration + " — " + text + ".",
                "Got it. " + duration + " from now: " + text + ".");
    }

    public static String playingRu(String track) {
        return pick(
                "Включаю «" + track + "».",
                "Запускаю «" + track + "».",
                "Готово, играет «" + track + "».",
                "Уже, «" + track + "».");
    }
    public static String playingEn(String track) {
        return pick(
                "Playing \"" + track + "\".",
                "Now playing \"" + track + "\".",
                "There you go — \"" + track + "\".",
                "Cued up \"" + track + "\".");
    }

    public static String unknownAppRu(String name) {
        return pick(
                "Не нашёл приложение «" + name + "».",
                "Такого приложения у вас не вижу: «" + name + "».",
                "«" + name + "» не установлен или мне не виден.");
    }
    public static String unknownAppEn(String name) {
        return pick(
                "Couldn't find an app called \"" + name + "\".",
                "No \"" + name + "\" installed, by the look of it.",
                "I don't see an app named \"" + name + "\".");
    }

    // ---- v3: notes / mail / calendar phrasing ----

    public static String noteSavedRu() {
        return pick("Заметка сохранена.",
                "Записал.",
                "Запомнил, заметка в списке.",
                "Внесено в заметки.");
    }
    public static String noteSavedEn() {
        return pick("Note saved.",
                "Got it — added to notes.",
                "Written down.",
                "Stored in your notes.");
    }
    public static String noteDeletedRu(String snippet) {
        String s = snippet == null ? "" : snippet.trim();
        if (s.length() > 60) s = s.substring(0, 60) + "…";
        if (s.isEmpty()) return pick("Заметка удалена.", "Готово, удалил.");
        return pick("Удалил заметку «" + s + "».",
                "Готово, заметки про «" + s + "» больше нет.");
    }
    public static String noteDeletedEn(String snippet) {
        String s = snippet == null ? "" : snippet.trim();
        if (s.length() > 60) s = s.substring(0, 60) + "…";
        if (s.isEmpty()) return pick("Note deleted.", "Done, removed.");
        return pick("Deleted the note about \"" + s + "\".",
                "Removed: \"" + s + "\".");
    }
    /** Russian-correct word form for plural nouns: 1 заметка / 2 заметки / 5 заметок. */
    public static String notesCountRu(int n) {
        int abs = Math.abs(n);
        int mod10 = abs % 10;
        int mod100 = abs % 100;
        if (mod10 == 1 && mod100 != 11) return "заметка";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return "заметки";
        return "заметок";
    }

    public static String mailComposeRu(String to) {
        return pick("Открыл письмо для " + to + ". Допишите и отправьте.",
                "Готов черновик для " + to + ".",
                "Письмо к " + to + " — текст можно дополнить.");
    }
    public static String mailComposeEn(String to) {
        return pick("Drafted an email to " + to + ". Add the text and send.",
                "Mail to " + to + " is ready for your edits.",
                "Composed a message for " + to + ".");
    }
    public static String mailFoundContactRu(String name, String email) {
        return pick("Нашёл " + name + " (" + email + "), открываю письмо.",
                name + " — " + email + ", черновик готов.");
    }
    public static String mailFoundContactEn(String name, String email) {
        return pick("Found " + name + " — " + email + ". Opening a draft.",
                name + " resolved to " + email + ".");
    }

    public static String calendarSummaryRu(int count) {
        if (count == 1) return "В этом окне одна встреча:";
        if (count >= 2 && count <= 4) return "В этом окне " + count + " встречи:";
        return "В этом окне " + count + " встреч:";
    }
    public static String calendarSummaryEn(int count) {
        return count == 1 ? "One event:" : (count + " events:");
    }
    public static String calendarCreatedRu(String title, long startMs) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(startMs);
        String hhmm = String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
        return pick("Записал «" + title + "» на " + hhmm + ".",
                "Готово, " + hhmm + " — «" + title + "».",
                "Создал встречу: «" + title + "», начало в " + hhmm + ".");
    }
    public static String calendarCreatedEn(String title, long startMs) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(startMs);
        String hhmm = String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
        return pick("Created \"" + title + "\" at " + hhmm + ".",
                "Booked \"" + title + "\" for " + hhmm + ".",
                "Done — \"" + title + "\" at " + hhmm + ".");
    }
}
