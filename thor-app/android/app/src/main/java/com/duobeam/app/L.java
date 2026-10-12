package com.duobeam.app;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/** UI strings: English by default, Polish selectable at runtime. */
final class L {

    static final String EN = "en", PL = "pl";
    private static String lang = EN;
    private static final Map<String, String[]> T = new HashMap<>();

    private static void t(String key, String en, String pl) {
        T.put(key, new String[] {en, pl});
    }

    static {
        // header and stream
        t("card_stream", "Stream", "Stream");
        t("start", "Start", "Start");
        t("stop", "Stop", "Stop");
        t("st_off", "Stopped.", "Zatrzymany.");
        t("st_starting", "Starting...", "Uruchamianie...");
        t("st_connected", "Connected to the receiver, starting video...", "Połączono z odbiornikiem, uruchamiam obraz...");
        t("st_streaming", "Streaming to the receiver: %s, %s fps", "Nadaję do odbiornika: %s, %s kl/s");
        t("st_no_tv", "Can't reach the receiver (%s). Is DuoBeam open on it? Trying again...", "Nie mogę połączyć się z odbiornikiem (%s). Czy DuoBeam jest na nim otwarty? Próbuję dalej...");
        t("st_error", "Something went wrong: %s", "Coś poszło nie tak: %s");
        t("st_foreign", "A stream is running that this app can't control (e.g. after clearing app data). It stops by itself within a few seconds.",
                "Działa stream, którego ta apka nie kontroluje (np. po wyczyszczeniu danych). Zatrzyma się sam w ciągu kilku sekund.");
        t("tv_receiving", "Receiver is getting the picture \u2714", "Odbiornik odbiera \u2714");
        t("tv_not_receiving", "Receiver gets nothing \u2716 - is DuoBeam open on it?", "Odbiornik nic nie dostaje \u2716 - czy DuoBeam jest na nim otwarty?");
        t("st_no_tv_set", "Find your receiver first (Find receiver below) or type the address shown on its screen.", "Najpierw znajdź odbiornik (Znajdź odbiornik niżej) albo wpisz adres widoczny na jego ekranie.");
        t("st_need_setup", "Do the one-time setup first (below).", "Najpierw zrób jednorazową konfigurację (niżej).");
        // receiver
        t("card_tv", "Receiver", "Odbiornik");
        t("tv_hint", "Open DuoBeam on your TV (or other receiver), then tap Find receiver.", "Otwórz DuoBeam na telewizorze (lub innym odbiorniku) i naciśnij Znajdź odbiornik.");
        t("find_tv", "Find receiver", "Znajdź odbiornik");
        t("finding", "Looking for receivers...", "Szukam odbiorników...");
        t("found_none", "No receiver found. Open DuoBeam on it and try again, or type the address shown on its screen.", "Nie znaleziono odbiornika. Otwórz na nim DuoBeam i spróbuj ponownie albo wpisz adres widoczny na jego ekranie.");
        t("found_title", "Choose your receiver", "Wybierz odbiornik");
        t("found_busy", "(busy)", "(zajęty)");
        t("tv_selected", "Receiver selected: %s", "Wybrano odbiornik: %s");
        // layout and quality
        t("card_layout", "Screens on the receiver", "Ekrany na odbiorniku");
        t("lay_side", "Side by side", "Obok siebie");
        t("lay_stack", "Top above bottom", "Jeden pod drugim");
        t("lay_pip", "Big top + small bottom", "Duży górny + mały dolny");
        t("lay_top", "Top screen only", "Tylko górny ekran");
        t("lay_bottom", "Bottom screen only", "Tylko dolny ekran");
        t("card_quality", "Picture quality", "Jakość obrazu");
        t("card_audio", "Sound", "Dźwięk");
        t("audio_off", "Only on this device", "Tylko na tym urządzeniu");
        t("audio_tv", "Only on receiver", "Tylko na odbiorniku");
        t("audio_both", "Receiver and this device", "Odbiornik i to urządzenie");
        t("audio_hint", "Game sound goes to the receiver together with the picture. \"Receiver and this device\" plays it in both places (this device is a bit ahead).", "Dźwięk gry leci do odbiornika razem z obrazem. \"Odbiornik i to urządzenie\" gra w obu miejscach (na urządzeniu trochę wcześniej).");
        t("q_low", "Low", "Niska");
        t("q_normal", "Normal", "Normalna");
        t("q_high", "High", "Wysoka");
        t("fps_60", "60 fps", "60 kl/s");
        t("fps_30", "30 fps (smaller load)", "30 kl/s (mniejsze obciążenie)");
        // setup
        t("card_setup", "One-time setup", "Jednorazowa konfiguracja");
        t("setup_need", "Needed once, so DuoBeam can capture the screens and the sound. Tap the button to open Developer options.",
                "Potrzebne raz, żeby DuoBeam mógł przechwytywać obraz i dźwięk. Naciśnij przycisk, żeby otworzyć opcje programisty.");
        t("setup_btn", "Set up", "Konfiguruj");
        t("setup_steps", "In Developer options: Wireless debugging > Pair device with pairing code. Type the 6-digit code here:",
                "W opcjach programisty: Debugowanie bezprzewodowe > Sparuj urządzenie za pomocą kodu. Wpisz tu 6-cyfrowy kod:");
        t("pair_searching", "Waiting for the pairing window...", "Czekam na okno parowania...");
        t("pair_found", "Pairing window found. Type the code.", "Okno parowania znalezione. Wpisz kod.");
        t("pair_code", "6-digit code", "kod (6 cyfr)");
        t("pair_btn", "Pair", "Paruj");
        t("pairing", "Pairing...", "Parowanie...");
        t("pair_ok", "Done! Setup finished.", "Gotowe! Konfiguracja zakończona.");
        t("pair_fail", "Pairing failed: %s. Open the pairing window again and type the new code.",
                "Parowanie nieudane: %s. Otwórz okno parowania jeszcze raz i wpisz nowy kod.");
        t("pair_no_port", "the pairing window isn't open", "okno parowania nie jest otwarte");
        t("pair_notif", "Code input is also in the notification (it may be silent in Do Not Disturb).",
                "Pole na kod jest też w powiadomieniu (w trybie Nie przeszkadzać może być ciche).");
        t("setup_done", "Ready. Nothing else to set up.", "Gotowe. Nie trzeba nic więcej konfigurować.");
        t("setup_grant", "Almost done: finishing setup...", "Prawie gotowe: kończę konfigurację...");
        t("setup_grant_fail", "Couldn't finish setup: %s", "Nie udało się dokończyć konfiguracji: %s");
        t("setup_retry", "Try again", "Spróbuj ponownie");
        t("working", "Working...", "Pracuję...");
        t("chk_dev", "Developer options are on", "Opcje programisty włączone");
        t("chk_dev_fix", "Turn them on: Settings > About > tap \"Build number\" 7 times.",
                "Włącz je: Ustawienia > Informacje o urządzeniu > 7 razy dotknij \"Numer kompilacji\".");
        t("chk_dev_btn", "Open About", "Otwórz Informacje");
        t("chk_wifi", "Wireless debugging is on (needs Wi-Fi)", "Debugowanie bezprzewodowe włączone (wymaga Wi-Fi)");
        t("chk_wifi_fix", "Developer options > Wireless debugging > turn it on.", "Opcje programisty > Debugowanie bezprzewodowe > włącz.");
        t("chk_wifi_btn", "Open developer options", "Otwórz opcje programisty");
        t("chk_pair", "DuoBeam is paired", "DuoBeam sparowany");
        t("chk_perm", "DuoBeam can turn these on by itself", "DuoBeam może sam to włączać");
        t("pair_again", "Pair again", "Sparuj ponownie");
        t("pair_what", "What is this code? Android shows a 6-digit code in Settings > Wireless debugging > Pair device with pairing code. It's needed only once, so DuoBeam can connect to this device.",
                "Co to za kod? Android pokazuje 6-cyfrowy kod w Ustawieniach > Debugowanie bezprzewodowe > Sparuj urządzenie za pomocą kodu. Potrzebny tylko raz, żeby DuoBeam mógł połączyć się z tym urządzeniem.");
        t("cancel", "Cancel", "Anuluj");
        t("finding_n", "Looking for receivers... %d%%", "Szukam odbiorników... %d%%");
        t("found_pick", "Found - tap your receiver:", "Znaleziono - dotknij swój odbiornik:");
        t("found_auto", "Found one receiver and selected it: %s", "Znaleziono jeden odbiornik i wybrano go: %s");
        t("pair_where", "Settings: %s screen  |  DuoBeam: %s screen", "Ustawienia: %s ekran  |  DuoBeam: %s ekran");
        t("scr_top", "top", "górny");
        t("scr_bottom", "bottom", "dolny");
        t("pair_port_link", "Port not detected? Type it yourself", "Port się nie wykrył? Wpisz go ręcznie");
        t("chk_auto", "(DuoBeam turns this on by itself)", "(DuoBeam włączy to sam)");
        t("boot_start", "Start streaming automatically when this device turns on", "Startuj stream automatycznie po włączeniu urządzenia");
        // options
        t("card_options", "Options", "Opcje");
        t("card_screens", "Screens while streaming", "Ekrany podczas streamu");
        t("card_screen", "Screen while streaming", "Ekran podczas streamu");
        t("scr_on", "On", "Włączony");
        t("scr_off", "Off (picture only on receiver)", "Wygaszony (obraz tylko na odbiorniku)");
        t("scr_both_on", "Both on", "Oba włączone");
        t("scr_top_off", "Top screen off", "Górny wygaszony");
        t("scr_bottom_off", "Bottom screen off", "Dolny wygaszony");
        t("scr_both_off", "Both off (picture only on receiver)", "Oba wygaszone (obraz tylko na odbiorniku)");
        t("scr_touch", "Touch works on dark screens", "Dotyk działa na ciemnych ekranach");
        t("scr_touch_short", "Touch on dark screen", "Dotyk na ciemnym");
        t("scr_hint", "Buttons always work. Tap a dark screen 3 times quickly to turn it on. With touch off, other taps on a dark screen do nothing; with touch on, the dark screen keeps controlling the game. Screens come back when the stream stops.",
                "Przyciski działają zawsze. Stuknij 3 razy szybko w ciemny ekran, żeby go włączyć. Przy wyłączonym dotyku inne stuknięcia w ciemny ekran nic nie robią, przy włączonym ciemny ekran dalej steruje grą. Ekrany wracają po zatrzymaniu streamu.");
        t("on", "ON", "WŁ");
        t("off", "OFF", "WYŁ");
        t("opt_wifi", "Keep Wi-Fi at full speed while streaming (fewer stutters)", "Wi-Fi na pełnej mocy podczas streamu (mniej przycięć)");
        t("opt_autostart", "Start streaming when I open this app", "Startuj stream po otwarciu tej apki");
        t("opt_overlay", "Side bar (right edge) to change screens during games", "Pasek przy prawej krawędzi do zmiany ekranów w trakcie gry");
        t("opt_overlay_perm", "Allow \"Display over other apps\" (the side bar needs it)",
                "Zezwól na \"Wyświetlanie nad innymi aplikacjami\" (potrzebne dla paska)");
        t("opt_tile", "Tip: you can add a DuoBeam tile to Quick Settings (pencil icon).",
                "Wskazówka: kafelek DuoBeam można dodać w szybkich ustawieniach (ikona ołówka).");
        // notifications / overlay / tile
        t("notif_channel", "Streaming", "Stream");
        t("notif_next", "Next layout", "Następny układ");
        t("ov_hide", "Hide until next start", "Schowaj do następnego startu");
        t("ov_off", "Turn off this side bar", "Wyłącz ten pasek");
        t("ov_touch_off", "Dark screen ignores touch. Tap it 3 times quickly to turn it on.",
                "Ciemny ekran nie reaguje na dotyk. Stuknij 3 razy szybko, żeby go włączyć.");
        t("ov_touch_on", "Dark screen still controls the game. Tap it 3 times quickly (or here) to turn it on.",
                "Ciemny ekran dalej steruje grą. Stuknij 3 razy szybko (albo tutaj), żeby go włączyć.");
        t("notif_bar", "Side bar", "Pasek boczny");
        t("ov_hide_short", "Hide", "Schowaj");
        t("ov_off_short", "Bar off", "Wyłącz pasek");
        t("tile_off", "off", "wyłączony");
        t("tile_setup", "set up first", "skonfiguruj");
        t("pair_channel", "Pairing", "Parowanie");
        t("pair_notif_title", "Type the pairing code", "Wpisz kod parowania");
        t("pair_notif_text", "Type the 6-digit code that Android shows in Wireless debugging.",
                "Wpisz 6-cyfrowy kod, który Android pokazuje w Debugowaniu bezprzewodowym.");
        t("pair_notif_action", "Type code", "Wpisz kod");
    }

    static void load(Context c) {
        lang = new Settings(c).lang();
    }

    static String lang() {
        return lang;
    }

    static void set(Context c, String l) {
        lang = l;
        new Settings(c).setLang(l);
    }

    static String s(String key) {
        String[] v = T.get(key);
        if (v == null) return key;
        return PL.equals(lang) ? v[1] : v[0];
    }

    static String f(String key, Object... args) {
        return String.format(s(key), args);
    }
}
