# CalGrid

Android home screen naptár widget (Android 12+) Google Tasks integrációval.

- **Elrendezések:** havi rács, ütemezés (agenda), vagy a kettő együtt (széles widgetben egymás mellett, magasban egymás alatt).
- **Naptárak:** a telefonon szinkronizált bármelyik naptár (Google, Exchange, Samsung, helyi…), widgetenként kiválasztható.
- **Google Tasks:** a feladatok a widgetben határidő szerint vagy külön szekcióban látszanak, és ott ki is pipálhatók. Az appban létrehozhatók, szerkeszthetők és törölhetők a feladatok, a listák pedig kezelhetők.
- **Automatikus frissítés:** kézi frissítés nem kell.

## Telepítés

A kész APK: [`release/CalGrid-debug.apk`](release/CalGrid-debug.apk). Másold a telefonra és telepítsd (engedélyezni kell az ismeretlen forrásból való telepítést), vagy:

```
adb install -r release/CalGrid-debug.apk
```

Ezután nyisd meg az appot, add meg a naptár-hozzáférést, és tedd ki a widgetet (hosszan nyomd a kezdőképernyőt, majd Widgetek → CalGrid).

## Hogyan frissül magától

| Változás | Mechanizmus |
|---|---|
| Naptáresemény (szinkron vagy bármely app) | WorkManager content-URI trigger a `CalendarContract` szolgáltatóra, ~2–10 s késéssel |
| Napváltás | AlarmManager éjfélkor |
| Lejárt esemény eltűnése | alarm a következő esemény végére |
| Óraállítás, időzóna, nyelv, újraindítás, app-frissítés | rendszer-broadcastok |
| Google Tasks | 15 percenként (a WorkManager minimuma), plusz azonnal az app megnyitásakor, minden helyi módosítás után és a widget ↻ gombjára |
| Tartalék | `updatePeriodMillis` 30 perc |

A Google Tasks API-nak nincs push értesítése, ezért egy máshol (pl. weben) létrehozott feladat legfeljebb kb. 15 perc múlva jelenik meg. A ↻ gombbal azonnal behúzható.

## Google Tasks beállítás (egyszeri)

A Tasks API-hoz OAuth kliens kell a Google Cloudban. Enélkül a „Csatlakozás” gomb *„Az OAuth kliens nincs beállítva”* hibát ad; a naptár ettől függetlenül működik.

1. Hozz létre egy Google Cloud projektet: <https://console.cloud.google.com/>.
2. **APIs & Services → Library**: engedélyezd a **Google Tasks API**-t.
3. **OAuth consent screen**:
   - User type: External, publishing status: Testing.
   - A *Test users* közé add hozzá a saját Google-fiókodat.
4. **Credentials → Create credentials → OAuth client ID** a következő adatokkal:
   - Application type: **Android**
   - Package name: `com.calgrid`
   - SHA-1: a debug kulcs lenyomata, amivel a mellékelt APK alá van írva: `8F:D8:72:0B:50:70:1E:C9:34:A4:6D:50:50:3B:DF:2E:AF:5D:72:F1`. Más gépen buildelve a `./gradlew signingReport` kiírja az aktuálisat.
5. Az appban: **Google Tasks → Csatlakozás**.

Kliens ID-t nem kell beírni a kódba: az Android OAuth kliens a package név és a SHA-1 alapján azonosítja az appot.

## Build

Android Studio vagy parancssor, JDK 17+:

```
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
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
