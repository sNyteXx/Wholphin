# Hidden Content Vault – Phase A: technische Analyse

> Analyse vor der Implementierung. Die fertige Architektur steht in
> [`hidden-content-vault.md`](hidden-content-vault.md).
> Stand: Wholphin `559abe2`, Jellyfin Kotlin SDK 1.7.1.

## 1. Referenz: Moonfin

Gelesen: `docs/custom/hidden-content-vault.md`, `lib/custom/hidden_vault/**`,
alle 99 Tests unter `test/custom/hidden_vault/` (Branch
`claude/affectionate-faraday-3vdq85`).

Übernommenes Verhalten (nicht die Implementierung):

| Regel | Moonfin-Quelle |
|---|---|
| Matching exakt, case-insensitive, trim + Whitespace-Läufe → ein Leerzeichen | `tag_match.dart`, Tests 1–6 |
| ANY-Semantik pro Library, Scope = Library-ID, eine Library → ein Vault | `hidden_tag_policy.dart`, Tests 10–12 |
| Fingerprint nur aus Vault-/Library-IDs + normalisierten Tags | `vault_config.dart`, Test 13 |
| Index: 1 Tag-Query pro Library (`ParentId`, `Recursive`, `Tags=a\|b`, OR), Ergebnis exakt nachvalidiert (Server matcht lockerer: `Ecchi!` ≙ `ecchi`) | `hidden_content_index.dart`, Tests 16–19 |
| Episoden/Staffeln erben über `SeriesId`/`SeasonId` | Tests 21–22, 31 |
| Container (BoxSet/Playlist) nur über eigene Tags | Test 28 |
| Tag-Treffer außerhalb des Index → „Verdacht“ → ein Rebuild entscheidet, Out-of-Scope wird gemerkt | Tests 33–34 |
| NORMAL filtert immer, auch bei geöffnetem Vault; nur item-spezifische Aufrufe im betretenen Vault sehen dessen Inhalt | Tests 42–44, 57–58, 64 |
| Virtuelles Paging ohne Duplikate/Lücken, Read-Ahead max. 3 Zusatz-Requests, 0 Zusatz-Requests ohne Hidden-Treffer | Tests 38–41 |
| Session nur im Speicher, Timeout 15 min, Lock-on-leave, Konto-/Serverwechsel sperrt | Tests 65–74 |
| PIN eigener Namespace, gehasht + gesalzen, 5 freie Versuche, dann 30 s × n (max. 15 min) | Tests 75–78 |
| Sync über DisplayPreferences, LWW über `updatedAt`, nur Regeln im Payload, Offline-Nachschieben, Sync aus, Wartezeit ≤ 2 s | Tests 79–84 |

Nicht übernommen (bewusst):

* Biometrie (`vault_biometrics`) – laut Auftrag nicht Teil von v1.
* Der 12-h-Prüf-Cache für Serien-Tags (`ItemTagResolver`, „unknown“-Verdicts).
  Wholphin bekommt stattdessen frischere Index-Trigger (Websocket
  `LibraryChanged`, 10-min-Staleness, App-Start, Config-Änderung); siehe §6.
* Signatur-basiertes virtuelles Paging für *alle* Listen: In Wholphin pagen
  praktisch nur `ApiRequestPager`-Instanzen; dort ist ein exaktes Verfahren
  möglich (§5).

## 2. Wholphin-Architektur (relevant)

* **Ein** `ApiClient`-Singleton: `services/hilt/AppModule.kt` →
  `jellyfin.createApi()` = `CoroutineContextApiClient` (nur Dispatcher-Wechsel).
  Andere `ApiClient`-Instanzen (Setup-Screens, Crash-Reporter) holen keine Items.
* SDK 1.7.1: `ApiClient` ist eine abstrakte Klasse; **jede** generierte API
  (`itemsApi`, `tvShowsApi`, …) endet in `request(method, pathTemplate,
  pathParameters, queryParameters, body): RawResponse`. `RawResponse.body`
  ist ein `ByteArray` → ein Decorator kann Antworten gezielt umbauen.
  Query-Parameter liegen typisiert vor (`startIndex`, `limit`, `fields`, …).
* `ApiRequestPager` / `RequestPager`: random-access Liste, Seite N =
  Positionen `[N·pageSize, (N+1)·pageSize)`, `totalCount` nur aus
  `TotalRecordCount` beim ersten Laden/Refresh, Seiten-Cache pro Seitennummer.
  **Kürzere Seiten erzeugen Löcher** (Platzhalterkarten, die nie laden).
* Navigation: `NavigationManager.backStack` (Navigation 3), `Destination` ist
  eine `@Serializable sealed class` → eigene Unterklassen nur in derselben
  Datei möglich. Back Stack wird in `onSaveInstanceState` serialisiert.
  ViewModels leben pro Back-Stack-Eintrag (`rememberViewModelStoreNavEntryDecorator`).
* `ServerRepository.current: StateFlow<CurrentUser?>` = Server + User.
* `DisplayPreferencesService`: `getDisplayPreferences(userId, id, client)`;
  `updateDisplayPreferences` liest intern immer die `default`-Prefs (Upstream-Bug,
  Zeile 41) → für eigenen Namespace nur das Lesen verwenden, Schreiben direkt.
* Profil-PIN: Klartext in Room (`users.pin`), keine Rate-Limits, Eingabe per
  D-Pad-Pfeilen + Ziffern (`ui/setup/PinEntry.kt`). → Für den Vault nicht
  wiederverwendbar (Sicherheit), aber die **Eingabe-Bausteine** (`PinArrowRow`,
  `PinEntryDots`, `BasicDialog`) und das D-Pad-Eingabekonzept passen.
* Settings: `AppPreference`-Modell + `PreferencesContent` (`when (pref)`),
  About-Gruppe enthält `InstalledVersion` (Klick = Release Notes,
  Long-Click = Debug). Beide Gesten sind belegt.
* Speicher: ACRA schickt Default-SharedPreferences mit, `allowBackup=true`
  → Vault-Daten gehören in eigene Dateien unter `noBackupFilesDir`.

## 3. Query-Wege (Inventar)

Legende: **P** = `ApiRequestPager`, **H** = `RequestHandler.execute/countMatching` direkt,
**D** = SDK direkt. Alle drei Wege laufen durch `ApiClient.request()`.

| Bereich | Weg | Endpunkt(e) |
|---|---|---|
| Home: Continue Watching / Next Up / Combined | D (`LatestNextUpService`) | `/UserItems/Resume`, `/Shows/NextUp`, `/Shows/{id}/Episodes` |
| Home: Recently Added | D | `/Items/Latest` (Array) |
| Home: Recently Released, By Parent, GetItems, Favorites | P/H | `/Items` |
| Home: Suggestions | IDs aus `SuggestionsCache` (Disk!) → H | `/Items?ids=` |
| Home: Genres / Studios | H | `/Genres`, `/Studios`, Genre-Artwork über `/Items` |
| Library-Grids (alle CollectionFolder*) | P, A–Z-Sprung H `countMatching` | `/Items` |
| Library „Recommended“-Tabs | P/H | `/UserItems/Resume`, `/Shows/NextUp`, `/Items` |
| Suche | D | `/Items` (searchTerm), Personen/Artists separat |
| Favoriten | P, A–Z H | `/Items?isFavorite=true` |
| Collections (BoxSet) | P/H | `/Items?parentId=<boxset>` |
| Playlists | P | `/Items?parentId=<playlist>` |
| Personen-Seite | P | `/Items?personIds=` |
| Similar | D | `/Items/{id}/Similar` (+ Album/Artist) |
| Extras / Trailer / Intros / Theme | D | `/Items/{id}/SpecialFeatures`, `LocalTrailers`, `Intros`, `ThemeSongs` |
| „View more“-Grid (`ItemGrid`) | P | beliebiger Handler, z. B. `/Items?ids=` |
| Screensaver (in-app + Daydream) | P | `/Items` (SortBy=Random) |
| Launcher Watch Next / „Recently added“-Kanal | D (Worker) | Resume/NextUp/`/Items/Latest` |
| Deep Links / Intents | D | `/Items/{id}` → Detailseite |
| Detailseiten (Movie, Series, Season, Episode, BoxSet, Playlist, Music …) | D | `/Items/{id}`, Staffeln `/Items?parentId=<series>`, Episoden `/Shows/{id}/Episodes` |
| Playback, Queue, Auto-Play, Next Episode | D/H | `/Items/{id}`, `PlaylistCreator` (Episodes/NextUp/Items/AdditionalParts), **POST `/Items/{id}/PlaybackInfo`** |
| Externer Player | D | `/Items/{id}` + `PlaylistCreator` |
| Musik-Queue / Instant Mix | P/D | `/Items`, `/Items/{id}/InstantMix` |
| Filter-Dropdowns | D | `/Items/Filters` (Tags!), Genres, Studios, Years |
| Admin/Report, DatePlayed, Removed-NextUp-Verwaltung | D | `/Items`, `/Items/{id}` |

Bestehende Filter (Removed-Next-Up, Screensaver-Altersfreigabe, Nutzerfilter)
sind lokal verteilt – es gibt keinen zentralen Filterpunkt.

## 4. Entscheidung: zentraler `ApiClient`-Decorator

`HiddenContentApiClient` ersetzt im Hilt-Provider den `ApiClient` (1 Hook)
und delegiert an den `CoroutineContextApiClient`. Er greift **nur** bei einer
Whitelist bekannter Item-Endpunkte (Pfad-Template + Methode) ein und
verändert dort nur zwei Dinge: Elemente aus `Items`/Arrays entfernen und
`TotalRecordCount` anpassen. Alles andere (Auth, Sessions, DisplayPreferences,
Bilder, Websocket, LiveTV, Genres, Personen …) geht unverändert durch.
Ohne konfigurierte Regeln: reiner Pass-through ohne JSON-Parse.

Damit sind alle oben gelisteten Wege – inklusive zukünftiger Upstream-Screens –
abgedeckt, ohne die rund 90 Aufrufstellen anzufassen.

Warum nicht `RequestHandler`/Pager-Ebene allein: verpasst alle D-Wege
(Latest, Resume, NextUp, Similar, Extras, Suche, `getItem`, Playback).

## 5. Paging-Strategie

* `ApiRequestPager` braucht exakte Positionen und exakte Gesamtzahlen (sonst
  Löcher/Platzhalter). Lösung: **ein** Hook in `ApiRequestPager.fetchPage` →
  `VisiblePaging` pro Pager-Instanz:
  * Seite 0 wird normal geladen. Passt die ganze Liste hinein → exakt, 0 Zusatz-Requests.
  * Sonst einmal ein leichtgewichtiges „Skelett“ derselben Query (nur IDs +
    Eltern-IDs + Tags, ohne Bilder/UserData) → Liste der sichtbaren
    Server-Positionen → jede Seite = genau ein Request auf den exakten
    Server-Bereich, Total exakt, Random-Access (A–Z-Sprung,
    `initialPosition`) exakt.
  * Library nicht konfiguriert → kein Eingriff.
* Direkte Listen (Home-Reihen, Similar, Latest …): Filter + begrenztes Read-Ahead
  (max. 3 Zusatz-Requests, Cap 200) nur für Top-N-Aufrufe (`startIndex` 0/leer);
  ohne Hidden-Treffer kein Zusatz-Request.
* Reine Zählungen (`limit=0`, A–Z-Sprung): Abzug der versteckten Treffer über
  `ids=<Index-IDs>`-Zählqueries (gechunkt).

## 6. Aktualität des Index

* Index persistiert pro Server+User mit Fingerprint; App-Start nutzt ihn sofort.
* Rebuild: Config-Änderung (blockierend), Alter > 10 min (Hintergrund),
  Websocket `LibraryChanged` (Hintergrund, entprellt), Verdacht (s. u.).
* Eigene Tags: Der Decorator hängt `Tags` an `fields` der Listen-Requests an.
  Ein Tag-Treffer, den der Index nicht kennt, ist ein Verdacht → bleibt
  versteckt, ein Rebuild entscheidet; liegt das Item außerhalb jeder
  Vault-Library, wird es als out-of-scope gemerkt (persistiert).

## 7. Zweite Sicherheitslinie

* Detail: Decorator lehnt `/Items/{id}` für versteckte Items mit 404 ab
  (auch Deep Links, Launcher, Intents, `refreshItem`) + UI-Gate in
  `DestinationContent` (neutrale Meldung, nichts vom Item wird geladen).
* Playback: Decorator lehnt `PlaybackInfo` ab + explizites Gate in
  `PlaybackViewModel.play()` für jedes Queue-Element (Start, Next, Previous,
  Auto-Play, Resume, Intents).

## 8. Geplante Upstream-Hooks

| Datei | Zweck |
|---|---|
| `services/hilt/AppModule.kt` | `ApiClient` dekorieren |
| `util/ApiRequestPager.kt` | exaktes Paging |
| `ui/nav/Destination.kt` | eine Destination für alle Vault-Routen |
| `ui/nav/DestinationContent.kt` | Vault-Routen rendern + Detail-Gate |
| `preferences/AppPreference.kt` + `ui/preferences/PreferencesContent.kt` | unauffälliger Einstieg in *Über* |
| `ui/playback/PlaybackViewModel.kt` | Playback-Gate |

Alles andere liegt unter `custom/hiddenvault/`, Strings in eigenen
Ressourcendateien (`hidden_vault_strings.xml`), Tests unter
`app/src/test/.../custom/hiddenvault/`.
