# Hidden Content Vault (Custom-Erweiterung dieses Forks)

> Fork-spezifisch. Alle Berührungspunkte mit Upstream-Code tragen den Marker
> `hidden-vault:` → `git grep "hidden-vault:"` listet sie nach jedem Upstream-Merge.
> Die Phase-A-Analyse (Query-Wege, Entscheidungsgrundlage) steht in
> [`hidden-content-vault-analysis.md`](hidden-content-vault-analysis.md).
> Funktionale Referenz: Moonfin-Core, Branch `claude/affectionate-faraday-3vdq85`.

## 1. Was der Vault tut

* Beliebige, **frei konfigurierte Jellyfin-Tags** blenden Inhalte vollständig aus der normalen
  Wholphin-Oberfläche aus. Ohne Konfiguration ist das Feature inaktiv; jeder Request geht
  unverändert durch (kein JSON-Parse).
* Matching: `trim`, Whitespace-Läufe → ein Leerzeichen, case-insensitive, sonst **exakt**
  (`Ecchi == ecchi`, `ecchi ≠ super-ecchi`, `adult ≠ adult animation`, `hidden ≠ hidden gem`).
* Pro Library mehrere Tags mit **ANY**-Semantik; Tags gelten nur in ihrer Library
  (Identität = Jellyfin-Library-ID). Eine Library gehört höchstens einem Vault.
* Zwei Kontexte: **NORMAL** (Hidden immer unsichtbar – auch bei entsperrtem Vault) und
  **VAULT(id)** (ausschließlich die Inhalte genau dieses Vaults).

## 2. Bedienung

1. Einstellungen → *Über* → **„Gerät“** (zeigt Hersteller/Modell/Android-Version, sieht aus
   wie eine Info-Zeile). Beim ersten Mal wird eine eigene Vault-PIN festgelegt, danach wird sie
   abgefragt. PIN = 4 Symbole im Wholphin-Stil: Ziffern **oder D-Pad-Richtungen**
   (funktioniert mit jeder Fernbedienung).
2. Privater Bereich: oben die Bereiche (Auswahl → Vault-Home), darunter *X bearbeiten*,
   *Bereich hinzufügen*, Sitzungs-Einstellungen, *Dieses Gerät* (Sync an/aus, PIN ändern,
   Sperren).
3. Bereich bearbeiten: Name, Bibliotheken (bereits von einem anderen Bereich belegte sind
   gesperrt), pro Bibliothek *Versteckte Tags in …* → Tag-Auswahl (vom Server geladene Tags der
   Library, Filterfeld, manuelles Hinzufügen). *Speichern* baut den Index (1 Request je Library)
   und lädt beim Verlassen des privaten Bereichs den normalen Home neu.

Im normalen Startbildschirm gibt es keinen Einstieg, keine Geste, keinen Hinweis.

## 3. Architektur

```
app/src/main/java/.../custom/hiddenvault/
  HiddenVault.kt                Fassade: aktiver Server+User, Service, Session, PIN
  HiddenVaultHooks.kt           alle Aufrufe aus Upstream-Code (Pager, Playback-Gate)
  model/                        reine Domäne (kein Android)
    TagMatch.kt                 Normalisierung + exaktes Matching
    ItemIds.kt                  eine Schreibweise für IDs (32 hex, lowercase)
    HiddenVaultConfig.kt        Vaults, Libraries, Tags, Settings, updatedAt, Fingerprint
    HiddenTagPolicy.kt          Library-ID → (Vault, Tag-Set)
    TagPickerModel.kt           Tag-Auswahl-Logik (stabile Reihenfolge)
  data/                         reine Daten-/Domänenlogik (kein Android)
    HiddenContentIndex.kt       Index + Builder (1 Tag-Query je Library, exakte Validierung)
    HiddenContentService.kt     pro Server+User: Config, Index-Lebenszyklus, Verdicts, Sync
    VaultRepository.kt          VAULT-Kontext-Queries (Rows, Grid, Suche)
    VaultStore.kt               Scope, Key-Value-Store (Datei unter noBackupFilesDir)
    JellyfinSources.kt          Index-Quelle + DisplayPreferences-Sync über das SDK
  visibility/                   der Leak-Schutz (kein Android)
    HiddenContentApiClient.kt   ApiClient-Decorator: Filter, Gates, Read-Ahead, Zählkorrektur
    EndpointCatalog.kt          Whitelist der Item-Endpunkte
    VaultAllowance.kt           wann ein Vault-Item in einem Aufruf sichtbar sein darf
    VisiblePaging.kt            exaktes Paging für ApiRequestPager
    QueryRewritingApiClient.kt  Tags-Feld / Skelett-Query für das Paging
    ItemRef.kt                  ItemRef + Verdict
  session/
    VaultSessionManager.kt      In-Memory-Unlock, Enter/Leave, Timeout, Kontowechsel
    VaultPinStore.kt            eigene PIN: PBKDF2-HMAC-SHA256, Salt, Lockout
  di/
    HiddenVaultModule.kt        Hilt: Store, HiddenVault, ungefilterter Client, EntryPoint
    HiddenVaultAndroid.kt       Scope-Wechsel, Ticker, Lock → Back Stack, Websocket
  ui/                           Compose-TV-Seiten aus Wholphin-Bausteinen
res/values{,-de}/hidden_vault_strings.xml   eigene Strings (kein Weblate-Konflikt)
app/src/test/java/.../custom/hiddenvault/   Tests (inkl. FakeJellyfin)
```

`model/`, `data/`, `visibility/`, `session/` und `HiddenVault.kt` haben **keine
Android-Abhängigkeit** (nur Jellyfin-SDK, Coroutines, kotlinx.serialization) und werden in den
Tests direkt gegen das echte SDK ausgeführt.

### 3.1 Warum ein `ApiClient`-Decorator

Wholphin hat genau **einen** `ApiClient`-Singleton (Hilt). Jede generierte SDK-API endet in
`ApiClient.request(...)`, die Antwort ist ein `RawResponse(body: ByteArray, …)`. Der Decorator
`HiddenContentApiClient` sitzt dort und erreicht damit **alle** ~90 Aufrufstellen (Home,
Library, Suche, Favoriten, Collections, Similar, Extras, Screensaver, Launcher-Worker,
Playback, Intents …) inklusive künftiger Upstream-Screens, ohne sie anzufassen.

Gegen die Risiken eines generischen Eingriffs:

* **Whitelist** (`EndpointCatalog`): nur bekannte Item-Endpunkte (Pfad-Template + Methode).
  Auth, Sessions, DisplayPreferences, Bilder, Websocket, LiveTV, Genres/Studios/Personen …
  gehen unverändert durch.
* Für die Whitelist werden nur zwei Dinge verändert: Elemente aus `Items`/Arrays entfernen,
  `TotalRecordCount` senken. Die restliche JSON-Struktur bleibt erhalten; das SDK
  deserialisiert wie gewohnt. Ohne Treffer werden die **Original-Bytes** zurückgegeben.
* Einzel-Items und `PlaybackInfo` versteckter Items werden mit `InvalidStatusException(404)`
  abgelehnt – exakt wie ein nicht existierendes Item.
* Die JSON-Verarbeitung läuft auf `WholphinDispatchers.Default`, nie auf dem Main-Thread.

## 4. Datenfluss

```
UI / ViewModel / Worker
  └─ ApiClient (HiddenContentApiClient)          hidden-vault: AppModule
       ├─ nicht whitelisted ──────────────► CoroutineContextApiClient ─► Server
       └─ whitelisted
            ├─ HiddenVault.activeService()  (erste Liste wartet ≤ 2 s auf Sync, Index bereit)
            ├─ Request (+ Tags im fields-Parameter) ─► Server
            ├─ Verdict je Item (O(1) Hash-Lookups)
            ├─ Verdacht? → Index-Rebuild (Single-Flight, ≤ 6 s), sonst out-of-scope merken
            └─ gefilterte Antwort (+ ggf. begrenztes Read-Ahead)
ApiRequestPager
  └─ hidden-vault: VisiblePaging (exakte Positionen) ─► ungefilterter Client ─► Server
Vault-Screens
  └─ VaultRepository ─► ungefilterter Client (Tag-Queries / Index-Abgleich)
```

## 5. Visibility-Regeln

`HiddenContentService.verdict(item)`:

1. Index-Treffer auf `Id`, `SeriesId`, `SeasonId`, `AlbumId` oder `ParentId`
   → **Hidden(vault)**. Episoden/Staffeln erben über die Serie; keine Abfrage pro Episode.
2. Sonst: eigene Tags (kommen über das `Tags`-Feld im normalen Request mit) matchen eine Regel
   * BoxSet/Playlist → Hidden ohne Vault (liegen in keiner Library, nie im Vault sichtbar)
   * kein Index (Build fehlgeschlagen) → Hidden (lieber zu viel)
   * sonst **Verdacht** → versteckt, bis ein Rebuild entscheidet; liegt das Item außerhalb
     jeder Vault-Library, wird es als *out-of-scope* gemerkt (persistiert) → sichtbar.
3. Sonst sichtbar.

`VaultAllowance` – wann ein Hidden-Item trotzdem durch darf (nur bei **betretenem** Vault,
nur Items **dieses** Vaults):

| Aufruf | Erlaubt |
|---|---|
| globale Listen (Home-Rows, Resume, Next Up ohne Serie, Latest, Suche, Library-Listen, `ids=`-Listen, Favoriten …) | **nie** |
| `/Items/{id}`, `PlaybackInfo` | das Item selbst |
| Kinder eines Ankers (`/Shows/{id}/Episodes|Seasons`, `/Items?parentId=<Serie/Staffel>`, `NextUp?seriesId=`) | Items mit Bezug zum Anker |
| Verwandte (`Similar`, `SpecialFeatures`, `LocalTrailers`, `InstantMix`, `AdditionalParts`, `Intros`) | Items aus dem Vault des Ankers |

Eine **Library ist nie Anker** – Vault-Titel tauchen also selbst im entsperrten Zustand nie
in normalen Library-Listen auf.

## 6. Index

* Pro konfigurierter Library **eine** Query
  `/Items?ParentId=<lib>&Recursive=true&Tags=a&Tags=b&Fields=Tags` (Jellyfin: ODER über die
  Tags, lockeres „CleanValue“-Matching) → jedes Ergebnis wird **exakt** gegen seine eigenen
  Tags geprüft. Seiten à 1000, max. 3 Libraries parallel. Zusätzlich einmal `/UserViews`
  (welche IDs Libraries sind – Optimierung für das Paging).
* Ergebnis: `Map<ItemId, (vaultId, libraryId)>`, persistiert pro Server+User mit
  Config-Fingerprint (`noBackupFilesDir/hidden_vault/`).
* Aktualität:
  * App-Start: gespeicherter Index sofort nutzbar.
  * älter als 10 min → Hintergrund-Rebuild beim nächsten Request.
  * Websocket `LibraryChanged` (nur solange eine Activity im Vordergrund ist) → entprellt (30 s, Scans lösen also höchstens alle 30 s einen Rebuild aus)
    Hintergrund-Rebuild.
  * App kommt in den Vordergrund und Index > 2 min alt → Hintergrund-Rebuild.
  * Config-Änderung → blockierender Rebuild vor dem Speichern-Ende.
  * Verdacht (s. o.) → Rebuild, der die Antwort abwartet (≤ 6 s).
* Gleichzeitige Aufrufer teilen sich einen Build (Single-Flight, fingerprint-genau).
* Fehlerfälle: eine serverseitig gelöschte Library (404) hat nichts zu verstecken; schlägt die
  Query einer Library vorübergehend fehl, behält sie ihre Einträge aus dem letzten Index.
  Scheitert der allererste Build, gilt der vorsichtige Tag-Fallback (eigene Tags verstecken,
  unabhängig von der Library) und es wird erst nach 30 s erneut gebaut – nicht bei jedem
  Request.

## 7. Paging

**`ApiRequestPager`** (Grids, View-More, Favoriten, Collections, Personen, Screensaver …)
adressiert Positionen: Seite N = `[N·pageSize, (N+1)·pageSize)`, Größe = `TotalRecordCount`.
Kurze Seiten würden Platzhalter-Löcher erzeugen. Deshalb (Hook in `fetchPage`):

1. Library ohne Regeln (bekannt aus `/UserViews`) → gar kein Eingriff.
2. Seite 0: ein normaler Request mit `pageSize + 20` Puffer. Ist die Liste damit komplett →
   exakt, **0 Zusatz-Requests**.
3. Sonst ein leichtes **Skelett** derselben Query (nur IDs/Eltern-IDs/Tags, keine Bilder,
   keine UserData; Chunks à 5000, Cap 20 000) → Liste der sichtbaren Server-Positionen.
4. Jede Seite = **genau ein Request** auf den exakten Server-Bereich; Total exakt;
   Random-Access (A–Z-Sprung, wiederhergestellte Scroll-Position) exakt; keine Duplikate,
   keine Lücken. Gegenprüfung der IDs; hat sich die Liste serverseitig geändert → Skelett neu
   (einmal).
5. Zufallssortierung (Screensaver): Positionen sind bedeutungslos → Seite einfach auffüllen.

**Direkte Listen** (Home-Rows, Latest, Similar, Suche): Filter + begrenztes Read-Ahead nur
für Top-N-Aufrufe (`startIndex` 0/leer) und nur, wenn eine Reihe unter ¾ von
`min(limit, 24)` gefallen ist: max. **3** Zusatz-Requests, wachsende Seitengröße bis 200.
Ohne Hidden-Treffer kein Zusatz-Request.

**Zählungen** (`limit=0`, A–Z-Sprung): Total minus versteckte Treffer derselben Query
(`ids=<Index-IDs der Library>` in Chunks à 100).

## 8. Zweite Sicherheitslinie (Gates)

* **Detail-Gate** (`DestinationContent`, vor jeder Seite mit Item-ID: Details, Serien-Übersicht,
  Playback, Playlist-Playback, Slideshow). Bekannte Items werden ohne Warten entschieden;
  sonst wird nichts gezeichnet, bis die Prüfung fertig ist. Abgelehnt → neutrales
  „Nicht verfügbar“. Weder Titel noch Artwork erscheinen. Deckt Deep Links, Intents,
  Launcher-Programme, wiederhergestellte Back Stacks und künftige Screens ab.
* Zusätzlich lehnt der Decorator `/Items/{id}` ab (z. B. `IntentService`, `refreshItem`,
  Detail-ViewModels).
* **Playback-Gate** in `PlaybackViewModel.play()` für **jedes** Element (Start, Next,
  Previous, Auto-Play, Queue, Resume) → abgelehnte Elemente werden übersprungen; zusätzlich
  verweigert der Decorator `POST /Items/{id}/PlaybackInfo`. Queues werden schon beim Aufbau
  gefiltert (PlaylistCreator nutzt die gefilterten Listen).

## 9. Session

* Nur im Speicher (`VaultSessionManager`); im Store gibt es keinen Schlüssel für einen
  Unlock-Zustand (Test). App-Neustart/Prozess-Tod → gesperrt.
* PIN öffnet den **privaten Bereich**; die Auswahl eines Bereichs entsperrt **nur diesen**.
* *Betreten* = die Vault-Seite liegt im Back Stack (ViewModel-Lebensdauer, Referenzzählung).
  Detail-/Player-Seiten darüber halten den Vault offen.
* Sperren bei: Verlassen (Default an), Inaktivität (Default 15 min, 5/15/30/60), manuell,
  Logout, User-/Server-Wechsel (`ServerRepository.current`), App-Neustart, gelöschtem Bereich.
  Aktivität: Vault-Inhalte, die ausgeliefert werden, und **Playback-Progress-Reports** von
  Vault-Inhalten (lange Folgen sperren nicht mitten drin).
* Beim Sperren werden alle Vault-Seiten und alles darüber aus dem Back Stack entfernt und der
  Backdrop geleert (`HiddenVaultAndroid`).

## 10. PIN

* Bewusst **getrennt** von Wholphins Profil-PIN (die ist Klartext in Room, ohne Rate-Limit).
* 4 Symbole (`0–9`, `U/R/D/L`), PBKDF2-HMAC-SHA256 (eigene Implementierung, läuft ab API 23),
  zufälliger 16-Byte-Salt, 10 000 Iterationen, konstante Vergleichszeit.
* Pro Server+User, nur auf diesem Gerät, nie synchronisiert, nicht im Backup
  (`noBackupFilesDir`), nicht in ACRA-Reports (keine SharedPreferences).
* 5 freie Fehlversuche, danach 30 s × (Fehlversuche − 5), max. 15 min; während der Sperre wird
  die PIN gar nicht geprüft; Zähler überleben Neustarts.

## 11. Sync

* Jellyfin-DisplayPreferences des Users: `id = wholphin-hidden-vault`,
  `client = wholphin-hidden-vault`, CustomPref `config` (eigener Namespace, kollidiert weder mit
  Wholphin- noch Web-Client-Einstellungen).
* Synchronisiert: Vaults, Library-IDs (+ Namens-Snapshots), Tags, Timeout, Lock-on-leave,
  Gesehene ausblenden. **Nicht**: PIN, Unlock-Zustand, Index, Out-of-Scope-Cache,
  Gerät-Einstellungen (Sync an/aus).
* Last-Writer-Wins über `updatedAt` (beim Speichern gesetzt). Pull einmal pro Sitzung, erste
  Listen warten höchstens 2 s, späteres Ergebnis wird sofort angewendet. Push nach jedem
  Speichern; offline gespeicherte Änderungen werden beim nächsten Sync nachgeschoben.
* Lesen über Wholphins `DisplayPreferencesService`; Schreiben direkt (dessen
  `updateDisplayPreferences` liest intern immer die `default`-Prefs – Upstream-Bug, Z. 41).

## 12. Upstream-Hooks

| Datei | Hook |
|---|---|
| `services/hilt/AppModule.kt` | `apiClient`: Client mit `HiddenVaultModule.wrap` dekorieren |
| `util/ApiRequestPager.kt` | `hiddenVaultPaging` + exaktes Paging in `fetchPage` |
| `ui/nav/Destination.kt` | `Destination.HiddenVault(route: VaultRoute)` |
| `ui/nav/DestinationContent.kt` | Detail-Gate vor dem `when` + Branch für `Destination.HiddenVault` |
| `ui/playback/PlaybackViewModel.kt` | Playback-Gate in `play()` |
| `preferences/AppPreference.kt` | `HiddenVaultSettings.Entry` in der *Über*-Gruppe |
| `ui/preferences/PreferencesContent.kt` | Branch, der den Eintrag rendert |

Dazu kommen nur neue Dateien. Merge-Hinweise:

* Konflikt in einer Hook-Datei → die markierten Zeilen wieder einsetzen.
* Neue SDK-Version: Pfad-Templates in `EndpointCatalog` mit
  `grep 'api.\`get\`<.*BaseItemDto' …/operations/*.kt` abgleichen.
* Neuer `RequestHandler` für Medien-Items → in `HiddenVaultHooks.pagingFor` eintragen
  (sonst greift nur der Decorator-Filter, Positionen könnten Lücken bekommen).
* `docs/` steht in Wholphins `.gitignore`; diese Dateien sind per `git add -f` versioniert.

## 13. Tests

`app/src/test/java/.../custom/hiddenvault/` – laufen auf der JVM gegen das **echte
Jellyfin-SDK**: `FakeJellyfin` implementiert `ApiClient.request()` mit realistischem
Server-Verhalten (Tags nur mit `fields=Tags`, ODER + lockeres CleanValue-Matching beim
Tag-Filter, `ids` ∩ `parentId`, Paging, NextUp/Resume/Latest/Similar/Episodes/Seasons,
Filters, UserViews, DisplayPreferences, PlaybackInfo).

| Datei | Inhalt (Moonfin-Referenz) |
|---|---|
| `TagMatchTest` | Matching, Scope, eine Library je Vault, Fingerprint, Codec, keine Defaults (1–15) |
| `HiddenIndexTest` | 1 Query je Library, exakte Validierung, Persistenz, Staleness, Regeländerung, gelöschte/ausfallende Library, Backoff, kein Unlock im Store (16–19, 47) |
| `NormalContextTest` | Latest, Resume, Next Up (Erbe), Grid, Suche, Favoriten, Genre/Person, Similar, Scope, adult/adult animation, Collections, Filter-Tags, Detail 404, Episoden/Staffeln, Tags im fields-Param, Pass-through (20–32, 45–46) |
| `VaultContextTest` (+ `SuspectTest`, `GatesTest`) | Verdacht/Out-of-scope, nachträglich getaggt, LibraryChanged; globale Listen bleiben gefiltert, Item-Calls im Vault, anderer Vault zu, Unlock ≠ Enter, Lock; Playback-/Detail-Gate, Mischqueue, PlaybackInfo, Deep Links, Playback als Aktivität (33–35, 42–44, 55–64) |
| `PagingTest` | 60 Items/jedes 3. hidden: keine Duplikate/Lücken, Reihenfolge, Total 40, Request-Zahl; Random-Access; 250 Episoden mit versteckter Staffel; 1-Request-Listen; Libraries ohne Regeln; Zufallssortierung; Vault-Kinder; Read-Ahead voll/Budget/0 Zusatz; A–Z-Zählung (38–41) |
| `SessionAndPinTest` | Neustart, Unlock≠Enter, getrennte Vaults, Lock-on-leave (Refcount), Timeout, Aktivität, User/Server/Logout, Lock-Events, privater Bereich, gelöschter Vault; PIN korrekt/falsch, gehasht+gesalzen, Scope, Symbole, Lockout-Plan, Neustart (65–78) |
| `VaultSyncTest` | TV→Handy, nur Regeln im Payload, LWW beidseitig, Offline, Sync aus, langsamer Server ≤ 2 s, Namespace (79–84) |
| `VaultRepositoryTest` | Grid per Server-Tag-Query, nur eigener Vault, Gesehene ausblenden, Merge-Reihenfolge, Memo pro Besuch (48–54) |
| `PerformanceTest` | 4 Libraries, ~38 000 Items, 500+ Tags, 311 Index-Einträge: Index = 4 Queries, Home ≤ 9 Requests, 0 Lookups pro Item, Neustart ohne Rebuild; 200 000 Checks gegen 50 000 IDs/5 000 Tags (85) |
| `TagPickerTest` (+ `DetailGateTest`) | stabile Reihenfolge beim Ankreuzen, Filter, manuelles Hinzufügen, 5 000 Tags; Schnellpfad des Detail-Gates (92–94) |

Ausführen: `./gradlew :app:testDebugUnitTest --tests "com.github.damontecres.wholphin.custom.hiddenvault.*"`

Nicht portiert: Biometrie-Tests (Biometrie ist nicht Teil von v1); Moonfin-Widget-Tests der
privaten Seiten (95–99, 87–91) sind durch die Logik-Tests oben + die manuelle Checkliste (§15)
ersetzt.

## 14. Bekannte Grenzen

* **Episoden einer gerade erst getaggten Serie**: Serien/Filme mit eigenem Tag werden sofort
  erkannt (Verdacht). Episoden tragen die Tags der Serie nicht; sie verschwinden, sobald der
  Index neu gebaut ist (Websocket `LibraryChanged` → ≤ 30 s; sonst ≤ 10 min bzw. App-Start).
  Detail- und Playback-Gate prüfen beim Öffnen erneut.
* Elternbeziehungen werden eine Ebene tief geprüft (`Id/SeriesId/SeasonId/AlbumId/ParentId`).
  Tief verschachtelte getaggte *Ordner* in gemischten Libraries vererben nur an direkte Kinder.
* Collections (BoxSets)/Playlists liegen in keiner Library: sie verschwinden nur mit eigenem
  konfiguriertem Tag; ihr Inhalt wird immer gefiltert. **Playlist-Umsortieren** nutzt
  Server-Indizes: enthält eine Playlist versteckte Items, können Verschiebungen um diese
  versetzt landen.
* **Genre-/Studio-Namen** werden nicht gefiltert (nur deren Inhalte). Ein Genre, das nur in
  versteckten Inhalten vorkommt, bleibt als leere Kachel sichtbar.
* `/Search/Hints` (von Wholphin nicht genutzt) liefert keine `SeriesId`; dort wird über
  Thumb-/Backdrop-Item-IDs angenähert.
* Seerr/TMDB-Rows zeigen externe Katalogdaten (keine lokalen Items) und werden nicht gefiltert;
  ein Klick auf ein verknüpftes Jellyfin-Item läuft durch das Detail-Gate.
* Bereits an den Android-TV-Launcher übergebene Programme (Watch Next / „Zuletzt
  hinzugefügt“) werden beim nächsten Worker-Lauf ersetzt (stündlich und beim Verlassen der
  App); ein Klick darauf läuft durch das Detail-Gate.
* Skelett-Paging bis 20 000 Server-Items je Query; dahinter werden keine versteckten Items mehr
  bei den Positionen berücksichtigt (gefiltert wird trotzdem).
* Sync ist Last-Writer-Wins auf die ganze Config.
* Jellyfin selbst (Dashboard, andere Clients) liegt außerhalb des Scopes.

## 15. Verifikationsstand und manuelle TV-Checkliste

In der Cloud-Umgebung dieses Forks war Googles Maven-Repository (`dl.google.com`,
`maven.google.com`) per Egress-Policy gesperrt; das Android-Gradle-Plugin und AndroidX lassen
sich dort nicht laden. Deshalb:

* **Verifiziert**: der komplette Domänen-/Visibility-/Session-Kern wurde mit Kotlin 2.4.20
  gegen Jellyfin SDK 1.7.1 kompiliert und die Testsuite oben (115 Tests) ist grün
  (Standalone-JVM-Projekt, das genau diese Quell- und Testdateien einbindet).
  ktlint 1.8.0 ist sauber.
* **Teilweise verifiziert**: die Android-Schicht (`ui/`, `di/`, `HiddenVaultHooks.kt` und der
  `ApiRequestPager`-Hook) wurde mit dem echten Kotlin- und Compose-Compiler gegen
  JetBrains-Compose-Artefakte sowie Stubs für tv-material3, Hilt-Android und die
  Upstream-Wholphin-Symbole kompiliert (Signaturen aus den Wholphin-Quellen übernommen).
  Nicht gelaufen sind AGP, Hilt-Codegenerierung (KSP) und die großen Upstream-Hook-Dateien
  (`DestinationContent`, `PreferencesContent`, `PlaybackViewModel`, `AppPreference`,
  `AppModule`; dort nur gelesen). Vor dem Merge daher lokal oder per CI:
  `./gradlew assembleDefaultDebug testDefaultDebugUnitTest`.

**Test-APK über GitHub** (`.github/workflows/fork-android-release.yml`, nur im Fork): ein Push,
der die eine Zeile in `.github/fork-release` ändert (z. B. `fork-vault-2`), baut
`assembleDefaultDebug`, lässt die Unit-Tests laufen und veröffentlicht die APKs als Pre-Release
mit diesem Tag. Voraussetzung: *Actions* sind im Fork aktiviert. Der Debug-Build hat die
Paket-ID `….debug` und installiert sich neben einer normalen Wholphin. Für Updates ohne
Deinstallation optional ein Secret `DEBUG_KEYSTORE_BASE64` (Android-`debug.keystore`, base64)
anlegen; sonst wird der Schlüssel im Actions-Cache des Branches gehalten.

Manuelle Prüfung auf Android TV / Fire TV (D-Pad):

1. Einstellungen → Über → *Gerät*: PIN festlegen (nur Pfeile), wiederholen; falsche PIN 6×
   → Sperrmeldung mit Sekunden.
2. Bereich anlegen, Library + Tag wählen (Filter tippen, Pause → Liste filtert; Ankreuzen
   verschiebt den Fokus nicht), Speichern.
3. Normaler Home, Library, Suche, Favoriten, Continue Watching, Next Up: Hidden-Titel fehlen;
   Grid-Ende ohne Platzhalter; A–Z-Sprung landet richtig.
4. Bereich öffnen: Vault-Home (Fokus startet auf erster Reihe; Hoch → Such-/Library-Buttons),
   Detailseite einer versteckten Serie, Folge abspielen, Auto-Play zur nächsten Folge.
5. Zurück bis aus dem Bereich → gesperrt; erneut öffnen braucht wieder den privaten Bereich.
6. Deep Link `wholphin://…?itemId=<hidden>` → „Nicht verfügbar“ bzw. Fehler-Toast.
7. User wechseln / App neu starten → alles gesperrt; Back-Stack-Wiederherstellung landet auf
   Home.
