package com.devin.jarvis.core;

import android.content.Context;

import com.devin.jarvis.commands.AlarmTimer;
import com.devin.jarvis.commands.AppLauncher;
import com.devin.jarvis.commands.MusicPlayer;
import com.devin.jarvis.commands.ReminderManager;
import com.devin.jarvis.commands.Translator;
import com.devin.jarvis.llm.OpenAiClient;

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
        this.llm = new OpenAiClient(settings.openAiKey());
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
            case UNKNOWN:
            default:
                doFallback(stripped);
                break;
        }
        return true;
    }

    private void ack() {
        say(ru() ? "Слушаю, сэр." : "At your service, sir.");
    }

    private void doOpen(String name) {
        AppLauncher.Match m = launcher.findApp(name);
        if (m == null) {
            say(ru()
                    ? "Не нашёл приложение «" + name + "», сэр."
                    : "I couldn't find an app called \"" + name + "\", sir.");
            return;
        }
        boolean ok = launcher.open(m);
        say(ok
                ? (ru() ? "Открываю " + m.label + "." : "Opening " + m.label + ".")
                : (ru() ? "Не удалось открыть " + m.label + "." : "Failed to open " + m.label + "."));
    }

    private void doClose(String name) {
        AppLauncher.Match m = launcher.findApp(name);
        if (m == null) {
            say(ru()
                    ? "Не вижу такого приложения, сэр."
                    : "I don't see that app, sir.");
            return;
        }
        // Best-effort kill of background processes.
        launcher.killBackground(m);
        // Then open App Info so user can press Force Stop if app is still in foreground.
        launcher.openAppInfo(m);
        say(ru()
                ? "Android не позволяет мне закрывать приложения напрямую без root, сэр. Я остановил фоновые процессы " + m.label + " и открыл страницу настроек — нажмите «Остановить» там."
                : "Android doesn't allow me to close foreground apps without root, sir. I've killed " + m.label + "'s background processes and opened its info page — tap Force Stop there.");
    }

    private void doTranslate(String text, String src, String dst) {
        if (text == null || text.isEmpty() || dst == null || dst.isEmpty()) {
            say(ru() ? "Что нужно перевести, сэр?" : "What should I translate, sir?");
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
        String where = r.usedSystemClock
                ? (ru() ? "в системных «Часах»" : "in the system Clock app")
                : (ru() ? "внутри Jarvis" : "in-app");
        say(ru()
                ? "Таймер на " + humanDuration(seconds) + " установлен " + where + ", сэр."
                : "Timer for " + humanDurationEn(seconds) + " set " + where + ", sir.");
    }

    private void doAlarm(int hour24, int minute) {
        AlarmTimer.Result r = alarmTimer.setAlarm(hour24, minute, "Jarvis");
        String hhmm = String.format("%02d:%02d", hour24, minute);
        if (!r.ok) {
            say(ru() ? "Не получилось поставить будильник." : "Failed to set the alarm.");
            return;
        }
        String where = r.usedSystemClock
                ? (ru() ? "в системных «Часах»" : "in the system Clock app")
                : (ru() ? "внутри Jarvis" : "in-app");
        say(ru()
                ? "Будильник на " + hhmm + " поставлен " + where + ", сэр."
                : "Alarm set for " + hhmm + " " + where + ", sir.");
    }

    private void doReminder(int seconds, String text) {
        boolean ok = reminders.schedule(seconds, text);
        say(ok
                ? (ru() ? "Напомню через " + humanDuration(seconds) + ": " + text : "I'll remind you in " + humanDurationEn(seconds) + ": " + text)
                : (ru() ? "Не удалось поставить напоминание." : "Couldn't set the reminder."));
    }

    private void doPlayMusic(String song) {
        if (song == null || song.isEmpty()) {
            say(ru() ? "Какую композицию включить, сэр?" : "Which song would you like, sir?");
            return;
        }
        reply.status(ru() ? "Ищу «" + song + "»…" : "Searching for \"" + song + "\"...");
        music.playByNameAsync(song, new MusicPlayer.Callback() {
            @Override public void onPlaying(MusicPlayer.Result r) {
                String niceTitle = (r.title == null || r.title.isEmpty()) ? song : r.title;
                if (r.artist != null && !r.artist.isEmpty()) niceTitle = niceTitle + " — " + r.artist;
                say(ru()
                        ? "Включаю «" + niceTitle + "», сэр."
                        : "Playing \"" + niceTitle + "\", sir.");
            }
            @Override public void onError(String reason) {
                say(ru()
                        ? "Не удалось найти «" + song + "», сэр. (" + reason + ")"
                        : "Couldn't find \"" + song + "\", sir. (" + reason + ")");
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
        say(ru() ? "Останавливаю воспроизведение, сэр." : "Stopping playback, sir.");
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

    private void doFallback(String userText) {
        if (settings.useLlm() && llm.hasKey()) {
            reply.status(ru() ? "Думаю…" : "Thinking...");
            llm.chatAsync(userText, settings.language(), memory, settings, new OpenAiClient.Callback() {
                @Override public void onReply(String r) {
                    say(r);
                    llm.updateSummaryAsync(memory, settings.language(), null);
                }
                @Override public void onError(String reason) {
                    say(ru()
                            ? "Я не понял команду, сэр. (Сетевая модель недоступна: " + reason + ")"
                            : "I didn't catch that, sir. (Model unavailable: " + reason + ")");
                }
            });
        } else {
            say(ru()
                    ? "Не понял команду, сэр. Скажите «Джарвис, что ты умеешь», чтобы я перечислил возможности."
                    : "I didn't catch that, sir. Say \"Jarvis, help\" to hear what I can do.");
        }
    }

    private void say(String text) {
        memory.appendAssistant(text);
        reply.say(text);
    }

    private boolean ru() { return "ru".equalsIgnoreCase(settings.language()); }

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
