# CalGrid

Android home screen naptár widget (Android 12+) Google Tasks integrációval.

- **Elrendezések:** havi rács, ütemezés (agenda), vagy a kettő együtt (széles widgetben egymás mellett, magasban egymás alatt).
- **Naptárak:** a telefonon szinkronizált bármelyik naptár (Google, Exchange, Samsung, helyi…), widgetenként kiválasztható.
- **Google Tasks:** a feladatok a widgetben határidő szerint vagy külön szekcióban látszanak, és ott ki is pipálhatók. Az appban létrehozhatók, szerkeszthetők és törölhetők a feladatok, a listák pedig kezelhetők.
- **Méretek:** a fejléc, a havi nézet és az ütemezés betűmérete, a sorköz, valamint a két nézet aránya widgetenként állítható.
- **Automatikus frissítés:** kézi frissítés nem kell.

## Telepítés

A kész APK: [`release/CalGrid.apk`](release/CalGrid.apk) (release build, a saját release kulccsal aláírva). Másold a telefonra és telepítsd (engedélyezni kell az ismeretlen forrásból való telepítést), vagy:

```
adb install -r release/CalGrid.apk
```

Ezután nyisd meg az appot, add meg a naptár-hozzáférést, és tedd ki a widgetet (hosszan nyomd a kezdőképernyőt, majd Widgetek → CalGrid).

Ha korábban a debug APK volt fent, azt előbb el kell távolítani (más kulccsal van aláírva), és a widgetet újra ki kell tenni.

## Hogyan frissül magától

| Változás | Mechanizmus |
|---|---|
| Naptáresemény (szinkron vagy bármely app) | WorkManager content-URI trigger a `CalendarContract` szolgáltatóra, ~2–10 s késéssel |
| Napváltás | AlarmManager éjfélkor |
| Elmúlt esemény áthúzása, „most” vonal, futó esemény idősávja | alarm a következő esemény kezdetére/végére, futó esemény alatt 5 percenként |
| Óraállítás, időzóna, nyelv, újraindítás, app-frissítés | rendszer-broadcastok |
| Google Tasks | 15 percenként (a WorkManager minimuma), plusz azonnal az app megnyitásakor, minden helyi módosítás után és a widget ↻ gombjára |
| Tartalék | `updatePeriodMillis` 30 perc |

A Google Tasks API-nak nincs push értesítése, ezért egy máshol (pl. weben) létrehozott feladat legfeljebb kb. 15 perc múlva jelenik meg. A ↻ gombbal azonnal behúzható.

## Google Tasks beállítás (egyszeri)

A Tasks API-hoz OAuth kliens kell a Google Cloudban. Enélkül a „Csatlakozás” gomb hibát ad; a naptár ettől függetlenül működik.

1. Hozz létre egy Google Cloud projektet: <https://console.cloud.google.com/>.
2. **APIs & Services → Library**: engedélyezd a **Google Tasks API**-t.
3. **Google Auth Platform (OAuth consent screen)**: User type External, majd **Audience → Publish app → In production**.
   - Ellenőrzést (verification) nem kell kérni: saját / családi használatra (max. 100 fiók) enélkül is működik.
   - *Testing* állapotban a Google 7 nap után visszavonja a hozzáférést, és újra kell csatlakozni, ezért kell az *In production*.
4. **Credentials → Create credentials → OAuth client ID** a következő adatokkal:
   - Application type: **Android**
   - Package name: `com.calgrid`
   - SHA-1: a release kulcs lenyomata, amivel a mellékelt APK alá van írva: `70:86:3C:A0:19:AC:D2:82:4C:18:A5:00:50:C3:2C:57:63:80:C8:2E`.
   - Debug buildhez külön Android kliens kell a gép debug kulcsának SHA-1-ével (`./gradlew signingReport`).
5. Az appban: **Google Tasks → Csatlakozás**. Első alkalommal fiókonként megjelenik a *„Google hasn't verified this app”* képernyő: **Advanced → Go to CalGrid (unsafe)**.
   - A Google Tasks kártya kiírja az app tényleges SHA-1-ét; ha a Csatlakozás hibát ad, ezt vesd össze a Cloud Console-ban megadottal.

Kliens ID-t nem kell beírni a kódba: az Android OAuth kliens a package név és a SHA-1 alapján azonosítja az appot.

## Release kulcs

A release build aláírása a repón kívül van: `~/.android/calgrid-keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`), ami a `~/.android/calgrid-release.jks` kulcsra mutat. Más helyet a `-Pcalgrid.keystoreProperties=<útvonal>` Gradle property-vel lehet megadni. A fájl nélkül a release build aláíratlan lesz.

**Mindkét fájlt mentsd el biztonságos helyre.** Ha elvesznek, a telepített appot csak eltávolítás után lehet frissíteni, és az OAuth kliensben is új SHA-1-et kell megadni.

## Build

Android Studio vagy parancssor, JDK 17+:

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk (aláírva, ha van release kulcs)
./gradlew testDebugUnitTest      # unit tesztek (agenda, havi rács, pending op logika)
```

## Felépítés

```
app/src/main/java/com/calgrid/
  CalGridApp.kt              Application + AppContainer (manuális DI)
  auth/                      Google Identity AuthorizationClient (Tasks scope)
  data/calendar/             CalendarContract lekérdezések (Instances → ismétlődők kibontva)
  data/tasks/                Tasks REST kliens (Ktor), Room cache, offline pending-op sor
  model/                     tiszta logika: agenda csoportosítás, havi rács
  settings/                  widgetenkénti beállítás + app prefs (DataStore)
  sync/                      workerek, alarmok, receiverek, WidgetUpdater
  widget/                    Glance widget, akciók, UI
  ui/                        Compose app: kezdőképernyő, feladatlisták, szerkesztő, widget-konfig
```
