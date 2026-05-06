package com.devin.jarvis.core;

import android.content.Context;

import com.devin.jarvis.commands.AlarmTimer;
import com.devin.jarvis.commands.AppLauncher;
import com.devin.jarvis.commands.CalendarHelper;
import com.devin.jarvis.commands.MailComposer;
import com.devin.jarvis.commands.MusicPlayer;
import com.devin.jarvis.commands.Notes;
import com.devin.jarvis.commands.ReminderManager;
import com.devin.jarvis.commands.Translator;
import com.devin.jarvis.llm.OpenAiClient;

import java.util.List;

/**
 * The decision center. Receives a user transcript, decides what to do,
 * executes the action, asks the Speaker to reply, and updates Memory.
 */
public class Brain {

    public interface ReplyHandler {
        /** Called from a worker thread. The service should marshal to main if needed. */
        void say(String text);
        /**
         * Append a chunk to the current utterance without interrupting playback.
         * Used by streaming LLM replies so audio starts before the full reply
         * finishes generating.
         */
        void sayChunk(String chunk);
        /** UI feedback such as "Listening...", "Translating..." etc. */
        void status(String text);
        void onShutdownRequested();
        void onMuteRequested();
    }

    private final Context ctx;
    private final Settings settings;
    private final Memory memory;
    private final ReplyHandler reply;

    private final AppLauncher launcher;
    private final Translator translator;
    private final AlarmTimer alarmTimer;
    private final ReminderManager reminders;
    private final MusicPlayer music;
    private final OpenAiClient llm;
    private final Notes notes;
    private final LongMemory longMemory;
    private final CalendarHelper calendar;
    private final MailComposer mailer;

    public Brain(Context ctx, Settings settings, Memory memory, ReplyHandler reply) {
        this.ctx = ctx.getApplicationContext();
        this.settings = settings;
        this.memory = memory;
        this.reply = reply;
        this.launcher = new AppLauncher(this.ctx);
        this.translator = new Translator();
        this.alarmTimer = new AlarmTimer(this.ctx);
        this.reminders = new ReminderManager(this.ctx);
        this.music = new MusicPlayer(this.ctx);
        this.notes = new Notes(this.ctx);
        this.longMemory = new LongMemory(this.ctx);
        this.calendar = new CalendarHelper(this.ctx);
        this.mailer = new MailComposer(this.ctx);
        this.llm = new OpenAiClient(settings.openAiKey(), settings, this.longMemory);
    }

    /** Called with the full raw transcript. Returns true if a wake word was found. */
    public boolean handleTranscript(String text) {
        if (text == null || text.isEmpty()) return false;
        String stripped = Intents.stripWake(text);
        if (stripped == null) return false; // no wake word
        return runStripped(stripped);
    }

    /**
     * Entry point for typed text from the in-app input field. Wake word is not
     * required — the user typing into Jarvis's own input is itself the wake.
     * If the user did include a wake word at the start, we strip it.
     */
    public boolean handleTypedText(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        String stripped = Intents.stripWake(t);
        if (stripped == null || stripped.isEmpty()) {
            // Either no wake word at all, or user typed just "Jarvis".
            // For typed input we treat the whole thing as the command.
            stripped = t;
        }
        return runStripped(stripped);
    }

    private boolean runStripped(String stripped) {
        if (stripped == null) return false;
        if (stripped.isEmpty()) {
            // user just said "Jarvis" — acknowledge.
            ack();
            return true;
        }
        String lang = settings.language();
        memory.appendUser(stripped);
        Intents.Parsed p = Intents.parse(stripped, lang);
        switch (p.type) {
            case OPEN_APP: doOpen(p.arg1); break;
            case CLOSE_APP: doClose(p.arg1); break;
            case TRANSLATE: doTranslate(p.arg1, p.arg3, p.arg2); break;
            case TIMER: doTimer(p.int1); break;
            case ALARM: doAlarm(p.int2, p.int3); break;
            case REMINDER: doReminder(p.int1, p.arg1); break;
            case PLAY_MUSIC: doPlayMusic(p.arg1); break;
            case STOP_MUSIC: doStopMusic(); break;
            case STOP_LISTENING: reply.onMuteRequested(); break;
            case SHUTDOWN: reply.onShutdownRequested(); break;
            case WHO_ARE_YOU: doWhoAreYou(); break;
            case HELP: doHelp(); break;
            case NOTES_ADD: doNotesAdd(p.arg1); break;
            case NOTES_READ: doNotesRead(); break;
            case NOTES_DELETE: doNotesDelete(p.arg1); break;
            case EMAIL_SEND: doEmailSend(p.arg1, p.arg2); break;
            case CALENDAR_QUERY: doCalendarQuery(p.arg1); break;
            case CALENDAR_CREATE: doCalendarCreate(p.arg1); break;
            case UNKNOWN:
            default:
                doFallback(stripped);
                break;
        }
        return true;
    }

    private void ack() {
        say(ru() ? Persona.ackRu() : Persona.ackEn());
    }

    private void doOpen(String name) {
        AppLauncher.Match m = launcher.findApp(name);
        if (m == null) {
            say(ru() ? Persona.unknownAppRu(name) : Persona.unknownAppEn(name));
            return;
        }
        boolean ok = launcher.open(m);
        say(ok
                ? (ru() ? Persona.openingRu(m.label) : Persona.openingEn(m.label))
                : (ru() ? "Не удалось открыть " + m.label + "." : "Failed to open " + m.label + "."));
    }

    private void doClose(String name) {
        AppLauncher.Match m = launcher.findApp(name);
        if (m == null) {
            say(ru() ? Persona.unknownAppRu(name) : Persona.unknownAppEn(name));
            return;
        }
        // Best-effort kill of background processes.
        launcher.killBackground(m);
        // Then open App Info so user can press Force Stop if app is still in foreground.
        launcher.openAppInfo(m);
        say(ru()
                ? "Без root напрямую закрыть нельзя. Фоновые процессы " + m.label + " остановил, страницу настроек открыл — нажмите «Остановить» там."
                : "Can't close foregrounded apps without root. I've killed " + m.label + "'s background processes and opened its info page — tap Force Stop there.");
    }

    private void doTranslate(String text, String src, String dst) {
        if (text == null || text.isEmpty() || dst == null || dst.isEmpty()) {
            say(ru() ? "Что нужно перевести?" : "What should I translate?");
            return;
        }
        reply.status(ru() ? "Перевожу…" : "Translating...");
        translator.translateAsync(text, src, dst, new Translator.Callback() {
            @Override public void onTranslated(String t) {
                say((ru() ? "Перевод: " : "Translation: ") + t);
            }
            @Override public void onError(String reason) {
                say(ru()
                        ? "Не получилось перевести: " + reason
                        : "Translation failed: " + reason);
            }
        });
    }

    private void doTimer(int seconds) {
        if (seconds <= 0) seconds = 60;
        AlarmTimer.Result r = alarmTimer.setTimer(seconds, "Jarvis");
        if (!r.ok) {
            say(ru() ? "Не получилось завести таймер." : "Couldn't set the timer.");
            return;
        }
        say(ru()
                ? Persona.timerSetRu(humanDuration(seconds), r.usedSystemClock)
                : Persona.timerSetEn(humanDurationEn(seconds), r.usedSystemClock));
    }

    private void doAlarm(int hour24, int minute) {
        AlarmTimer.Result r = alarmTimer.setAlarm(hour24, minute, "Jarvis");
        String hhmm = String.format("%02d:%02d", hour24, minute);
        if (!r.ok) {
            say(ru() ? "Не получилось поставить будильник." : "Failed to set the alarm.");
            return;
        }
        say(ru()
                ? Persona.alarmSetRu(hhmm, r.usedSystemClock)
                : Persona.alarmSetEn(hhmm, r.usedSystemClock));
    }

    private void doReminder(int seconds, String text) {
        boolean ok = reminders.schedule(seconds, text);
        if (!ok) {
            say(ru() ? "Не удалось поставить напоминание." : "Couldn't set the reminder.");
            return;
        }
        say(ru()
                ? Persona.reminderSetRu(humanDuration(seconds), text)
                : Persona.reminderSetEn(humanDurationEn(seconds), text));
    }

    private void doPlayMusic(String song) {
        if (song == null || song.isEmpty()) {
            say(ru() ? "Какую композицию включить?" : "Which song would you like?");
            return;
        }
        reply.status(ru() ? "Ищу «" + song + "»…" : "Searching for \"" + song + "\"...");
        music.playByNameAsync(song, new MusicPlayer.Callback() {
            @Override public void onPlaying(MusicPlayer.Result r) {
                String niceTitle = (r.title == null || r.title.isEmpty()) ? song : r.title;
                if (r.artist != null && !r.artist.isEmpty()) niceTitle = niceTitle + " — " + r.artist;
                say(ru() ? Persona.playingRu(niceTitle) : Persona.playingEn(niceTitle));
            }
            @Override public void onError(String reason) {
                say(ru()
                        ? "Не удалось найти «" + song + "». (" + reason + ")"
                        : "Couldn't find \"" + song + "\". (" + reason + ")");
            }
        });
    }

    private void doStopMusic() {
        // Hard-stop the running service directly first — this is synchronous
        // (mutes the player on whatever thread we're on) and won't be defeated
        // by a pending volume-restore from the duck. The intent-based stop
        // below is a belt-and-braces in case the in-process reference has
        // somehow been cleared.
        com.devin.jarvis.commands.MusicService.stopIfPlaying();
        music.stopInApp();
        say(ru() ? Persona.stopMusicRu() : Persona.stopMusicEn());
    }

    private void doWhoAreYou() {
        say(ru()
                ? "Я — Джарвис, ваш персональный ассистент на этом устройстве. Программа, безусловно, но с характером."
                : "I'm Jarvis — your personal assistant on this device. A program, technically, but with a certain disposition.");
    }

    private void doHelp() {
        say(ru()
                ? "Я умею открывать и закрывать приложения, переводить слова, ставить таймеры, будильники и напоминания, включать музыку, отвечать на вопросы. Назовите меня по имени и говорите."
                : "I can open and close apps, translate, set timers, alarms and reminders, play music, and answer questions. Just say my name and ask.");
    }

    // ---- v3 handlers ----

    private void doNotesAdd(String text) {
        if (text == null || text.trim().isEmpty()) {
            say(ru() ? "Что записать?" : "What should I write down?");
            return;
        }
        long id = notes.add(text.trim());
        if (id < 0) {
            say(ru() ? "Не получилось сохранить." : "Couldn't save the note.");
            return;
        }
        say(ru() ? Persona.noteSavedRu() : Persona.noteSavedEn());
    }

    private void doNotesRead() {
        List<Notes.Note> all = notes.list();
        if (all.isEmpty()) {
            say(ru() ? "Заметок пока нет." : "You have no notes yet.");
            return;
        }
        int n = Math.min(5, all.size());
        StringBuilder sb = new StringBuilder();
        sb.append(ru()
                ? ("У вас " + all.size() + " " + Persona.notesCountRu(all.size()) + ". ")
                : ("You have " + all.size() + (all.size() == 1 ? " note. " : " notes. ")));
        sb.append(ru() ? "Последние: " : "Latest: ");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append("; ");
            sb.append(all.get(i).text);
        }
        sb.append('.');
        say(sb.toString());
    }

    private void doNotesDelete(String query) {
        boolean removed;
        String snippet = null;
        if (query == null || query.trim().isEmpty()) {
            // «удали заметку» без квалификатора — сносим самую свежую.
            List<Notes.Note> all = notes.list();
            if (all.isEmpty()) {
                say(ru() ? "Заметок нет." : "No notes to delete.");
                return;
            }
            snippet = all.get(0).text;
            removed = notes.deleteById(all.get(0).id);
        } else {
            snippet = notes.deleteByQuery(query.trim());
            removed = snippet != null;
        }
        if (!removed) {
            say(ru() ? "Ничего подходящего не нашёл." : "Nothing matching found.");
            return;
        }
        say(ru() ? Persona.noteDeletedRu(snippet) : Persona.noteDeletedEn(snippet));
    }

    private void doEmailSend(String to, String subjectOrBody) {
        if (to == null || to.trim().isEmpty()) {
            say(ru() ? "Кому отправить письмо?" : "Whom should I email?");
            return;
        }
        String trimmed = to.trim();
        String address = trimmed.contains("@") ? trimmed : null;
        String resolvedDisplayName = null;
        if (address == null) {
            MailComposer.ContactMatch hit = mailer.findContactEmail(trimmed);
            if (hit != null) {
                address = hit.email;
                resolvedDisplayName = hit.displayName;
            }
        }
        if (address == null || address.isEmpty()) {
            // Открываем почтовый клиент с пустым адресатом и
            // именем контакта в теме — пусть юзер выберёт вручную.
            boolean ok = mailer.compose("",
                    subjectOrBody == null ? "" : subjectOrBody,
                    ru() ? ("Контакт: " + trimmed) : ("Contact: " + trimmed));
            say(ru()
                    ? (ok ? ("Адрес «" + trimmed + "» не нашёл в контактах. Открыл почту — введите ручно.")
                            : "Не получилось открыть почту.")
                    : (ok ? ("Couldn't find \"" + trimmed + "\" in contacts. Opened mail — fill in the address.")
                            : "Couldn't open the mail app."));
            return;
        }
        boolean ok = mailer.compose(address, subjectOrBody == null ? "" : subjectOrBody, "");
        if (!ok) {
            say(ru() ? "Не получилось открыть почту." : "Couldn't open the mail app.");
            return;
        }
        String label = resolvedDisplayName != null ? resolvedDisplayName : address;
        say(ru() ? Persona.mailComposeRu(label) : Persona.mailComposeEn(label));
    }

    private void doCalendarQuery(String raw) {
        if (!calendar.canRead()) {
            say(ru() ? "Нужно разрешение на календарь. Откройте настройки и выдайте доступ."
                    : "I need calendar permission. Please grant it in app settings.");
            return;
        }
        DateTimeParser.Result range = DateTimeParser.parseRu(raw);
        long start, end;
        if (range != null && range.startMs > 0) {
            start = range.startMs;
            end = range.endMs > range.startMs ? range.endMs : start + 24L * 3600_000;
        } else {
            // По умолчанию — следующие 24 часа.
            start = System.currentTimeMillis();
            end = start + 24L * 3600_000;
        }
        List<CalendarHelper.Event> events;
        try {
            events = calendar.listEvents(start, end);
        } catch (SecurityException se) {
            say(ru() ? "Нужно разрешение на календарь."
                    : "I need calendar permission.");
            return;
        }
        if (events == null || events.isEmpty()) {
            say(ru() ? "На этот период встреч нет." : "No events on the calendar for that period.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ru() ? Persona.calendarSummaryRu(events.size())
                : Persona.calendarSummaryEn(events.size())).append(' ');
        int max = Math.min(5, events.size());
        for (int i = 0; i < max; i++) {
            if (i > 0) sb.append("; ");
            CalendarHelper.Event e = events.get(i);
            sb.append(CalendarHelper.formatEventLine(e, ru()));
        }
        sb.append('.');
        say(sb.toString());
    }

    private void doCalendarCreate(String raw) {
        if (!calendar.canWrite()) {
            say(ru() ? "Нужно разрешение на календарь. Откройте настройки и выдайте доступ."
                    : "I need calendar permission. Please grant it in app settings.");
            return;
        }
        DateTimeParser.Result when = DateTimeParser.parseRu(raw);
        if (when == null || when.startMs <= 0) {
            say(ru() ? "На какое время создать встречу?" : "What time should I create the meeting?");
            return;
        }
        String title = DateTimeParser.stripDateTimeRu(raw);
        if (title == null || title.trim().isEmpty()) title = ru() ? "Встреча" : "Meeting";
        long durationMin = when.endMs > when.startMs
                ? Math.max(15, (when.endMs - when.startMs) / 60_000L)
                : 60L;
        long id;
        try {
            id = calendar.createEvent(title, when.startMs, durationMin, null, null);
        } catch (SecurityException se) {
            say(ru() ? "Нужно разрешение на календарь."
                    : "I need calendar permission.");
            return;
        }
        if (id <= 0L) {
            say(ru() ? "Не получилось создать встречу." : "Couldn't create the event.");
            return;
        }
        say(ru() ? Persona.calendarCreatedRu(title, when.startMs)
                : Persona.calendarCreatedEn(title, when.startMs));
    }

    private void doFallback(String userText) {
        if (settings.useLlm() && llm.hasKey()) {
            reply.status(ru() ? "Думаю…" : "Thinking...");
            llm.chatAsync(userText, settings.language(), memory, settings, new OpenAiClient.Callback() {
                @Override public void onReply(String r) {
                    // Сначала вытаскиваем FACT-маркеры в долгую память,
                    // потом убираем их из текста, чтобы Джарвис не озвучивал
                    // служебные строки.
                    if (settings.longMemory()) {
                        try { longMemory.extractAndStoreFacts(r); } catch (Throwable ignored) {}
                    }
                    say(stripFactLines(r));
                    llm.updateSummaryAsync(memory, settings.language(), null);
                }
                @Override public void onError(String reason) {
                    say(ru()
                            ? "Не понял команду. (Сетевая модель недоступна: " + reason + ")"
                            : "I didn't catch that. (Model unavailable: " + reason + ")");
                }
            });
        } else {
            say(ru()
                    ? "Не понял команду. Скажите «Джарвис, что ты умеешь», чтобы я перечислил возможности."
                    : "I didn't catch that. Say \"Jarvis, help\" to hear what I can do.");
        }
    }

    private void say(String text) {
        memory.appendAssistant(text);
        reply.say(text);
    }

    private boolean ru() { return "ru".equalsIgnoreCase(settings.language()); }

    /** Removes "FACT: ..." marker lines from the LLM reply before TTS. */
    private static String stripFactLines(String reply) {
        if (reply == null || reply.isEmpty()) return reply;
        String[] lines = reply.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String stripped = line == null ? "" : line.replaceAll("^[\\s\\*\\-•]+", "").trim();
            String upper = stripped.toUpperCase();
            if (upper.startsWith("FACT:") || upper.startsWith("[FACT]")
                    || upper.startsWith("FACT ") || upper.startsWith("\u0424\u0410\u041a\u0422:")) {
                continue;
            }
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString().trim();
    }

    private static String humanDuration(int seconds) {
        if (seconds < 60) return seconds + " сек";
        if (seconds < 3600) {
            int m = seconds / 60;
            int s = seconds % 60;
            return s == 0 ? (m + " мин") : (m + " мин " + s + " сек");
        }
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        return m == 0 ? (h + " ч") : (h + " ч " + m + " мин");
    }

    private static String humanDurationEn(int seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) {
            int m = seconds / 60;
            int s = seconds % 60;
            return s == 0 ? (m + "m") : (m + "m " + s + "s");
        }
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        return m == 0 ? (h + "h") : (h + "h " + m + "m");
    }
}
