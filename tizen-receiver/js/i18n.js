// Receiver UI texts: English by default, Polish via left/right on the remote; the choice is persisted.
var I18N = {
  en: { flag: 'EN', waiting: 'Waiting for the handheld...', connected: 'Handheld connected - starting picture...',
    gone: 'Handheld disconnected - waiting...', stopped: 'Handheld stopped sending - waiting...',
    novideo: 'The handheld is connected, but no video is coming. Check the handheld.',
    badsize: 'This TV cannot play the picture from the handheld ({size}). Update DuoBeam on the handheld (0.8.4 or newer fits the picture to the TV) or pick another layout.',
    addrLabel: 'This receiver\'s address (the DuoBeam app finds it with "Find receiver"):',
    steps: 'On the handheld: open <b>DuoBeam</b> and tap <b>Start</b>.<br><small>First time only: tap "Set up" in DuoBeam and follow the steps.</small>',
    keys: 'OK info &middot; &uarr;/&darr; delay vs smoothness &middot; &larr;/&rarr; language &middot; Back exit',
    switching: 'Switching layout...',
    modes: ['lowest delay', 'balanced', 'smoothest'], mode: 'Mode', frames: 'frames', buffer: 'buffer', receiver: 'receiver', latency: 'delay' },
  pl: { flag: 'PL', waiting: 'Czekam na konsolę...', connected: 'Konsola połączona - uruchamiam obraz...',
    gone: 'Konsola rozłączona - czekam...', stopped: 'Konsola przestała nadawać - czekam...',
    novideo: 'Konsola jest połączona, ale obraz nie dochodzi. Sprawdź konsolę.',
    badsize: 'Ten telewizor nie odtworzy obrazu z konsoli ({size}). Zaktualizuj DuoBeam na konsoli (od 0.8.4 obraz jest dopasowany do telewizora) albo wybierz inny układ.',
    addrLabel: 'Adres tego odbiornika (apka DuoBeam znajdzie go przyciskiem "Znajdź odbiornik"):',
    steps: 'Na konsoli: otwórz <b>DuoBeam</b> i naciśnij <b>Start</b>.<br><small>Tylko za pierwszym razem: naciśnij w DuoBeam "Konfiguruj" i postępuj według kroków.</small>',
    keys: 'OK info &middot; &uarr;/&darr; opóźnienie vs płynność &middot; &larr;/&rarr; język &middot; Wstecz wyjście',
    switching: 'Zmieniam układ...',
    modes: ['najmniejsze opóźnienie', 'zrównoważony', 'najpłynniejszy'], mode: 'Tryb', frames: 'klatki', buffer: 'bufor', receiver: 'odbiornik', latency: 'opóźnienie' }
};
var RECEIVER_VERSION = '0.8.9';
var lang = 'en';
try { lang = localStorage.getItem('duobeam.lang') === 'pl' ? 'pl' : 'en'; } catch (e) {}
function tr(k) { return I18N[lang][k]; }
