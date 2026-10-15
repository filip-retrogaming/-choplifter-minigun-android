# Choplifter III – MINIGUN Android (alpha)

**Samostalna Android aplikacija:** ima svoju ikonu, prikazuje igru u landscape načinu, podržava višedodirne kontrole, Bluetooth gamepad, glazbu/zvuk i spremanje pozicije. **Nije potreban zasebno instaliran emulator.** U APK-u se koristi integrirana libretro implementacija Snes9x.

> STATUS: Android Studio projekt i JNI/C++ most su pripremljeni. Nije priložen gotovi APK. Ovdje nije bio dostupan Android SDK, pa aplikacija nije kompajlirana niti testirana na mobitelu. Projekt je prototip, ne provjereni finalni proizvod.

## Kako dobiti APK (najjednostavnije: GitHub Actions)

1. Na GitHubu kreiraj **privatni** repozitorij `choplifter-minigun-android`.
2. Raspakiraj ovaj ZIP i u repozitorij prenesi **sve sadržane datoteke**, uključujući `.github/workflows/build-apk.yml`.
3. Na stranici repozitorija odaberi **Actions → Build Choplifter Minigun APK → Run workflow**. Kod prvog `git push`-a izgradnja se može pokrenuti sama.
4. Kad postupak uspije, otvori završeni workflow i preuzmi **Artifacts → Choplifter-Minigun-Android-APK**. U njemu je `app-debug.apk`.
5. Prenesi APK na Android uređaj i instaliraj ga. Android će možda zahtijevati privremenu dozvolu *Install unknown apps* za aplikaciju kojom ga otvaraš.

APK je razvojni, lokalno instalabilan (debug-potpisa), **ne radi se o Google Play izdanju**. Korisnikov ROM se **ne nalazi u APK-u**.

## Kako pokrenuti igru

- Pokreni aplikaciju **Choplifter MINIGUN**.
- Pritisni **ROM** i odaberi `Choplifter III (U) (54553).smc` (original iz ovog projekta) **ili** `Choplifter_III_MINIGUN_TEST.smc`.
- Ako odabereš original s CRC32 `227E2D1F`, aplikacija će sama primijeniti naš verificirani IPS patch i potvrditi CRC32 `07DFFA28`. Za već patchirani ROM neće primijeniti patch drugi put.
- Od tada se igra pokreće izravno preko ikone aplikacije, bez ponovnog odabira datoteke.
- Pritiskom na **SAVE** sprema se puna pozicija emulatora, a **LOAD** je vraća. SRAM se automatski sprema pri izlasku.

## Kontrole

- Strelice na ekranu: kretanje
- **B**: glavni mitraljez, Minigun zakrpa (brojač s 15 na 2)
- **Y**: specijalno oružje
- **A, X, L, R, Start, Select**: ostale SNES tipke
- Bluetooth gamepad: uobičajeni Android/Xbox raspored je mapiran na položaje SNES tipki (fizički A → SNES B, fizički X → SNES Y); smjerovi i ramena su podržani

## Lokalna izgradnja (Android Studio)

1. Instaliraj Android Studio i otvori ovu mapu kao projekt.
2. Potreban je JDK 17, Android SDK 35 i Android NDK 27.0.12077973 te CMake 3.22.1. Android Studio može preuzeti komponente.
3. Koristi Gradle **8.13** (projekt nema uključenu Gradle wrapper JAR datoteku jer ovaj radni sustav nema Gradle). Po potrebi generiraj wrapper pomoću lokalno instaliranog Gradlea: `gradle wrapper --gradle-version 8.13`.
4. Pokreni `:app:assembleDebug`. Build će s [Libretro buildbota](https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/) preuzeti Snes9x `.so` u odgovarajuću mapu. Za izgradnju je potreban internet.
5. Rezultat: `app/build/outputs/apk/debug/app-debug.apk`.

Ako buildbot nije dostupan, ponovno pokreni build ili ručno postavi dopuštenu arm64 Snes9x libretro biblioteku na `app/src/main/jniLibs/arm64-v8a/libsnes9x_libretro_android.so`.

## Tehnički detalji

- Android API 26+, **arm64-v8a** (moderni 64-bitni Android telefoni).
- Android Java UI + JNI C++ bridge + libretro/Snes9x emulator; SNES emulator je sastavni dio aplikacije nakon izgradnje.
- Pikseli su crtani unutar `Bitmap` objekta, u 60 FPS petlji; zvuk preko `AudioTrack`.
- Automatski SRAM i ručni save state spremaju se u interni privatni prostor aplikacije.
- Dostupna implementacija ne uključuje cloud save, upravljanje s više ROM-ova, remapiranje tipki ni napredne postavke emulatora.
- Minigun hack i njegova poznata usporavanja pri mnogo metaka ostaju onakvi kakvi su u testiranom SNES ROM-u. Android emulacija sama po sebi ne uklanja usporavanja izvornog stroja.

## Autorska prava i licence

- SNES ROM igre **nije uključen** u ovaj izvorni projekt ni u APK. Uvezi vlastitu kopiju kojoj imaš pravo pristupa. IPS patch sadrži samo promijenjene bajtove.
- Licencna obavijest Snes9x nalazi se i unutar aplikacije kao `assets/SNES9X_LICENSE.txt`. Snes9x core koristi licencu **za nekomercijalnu upotrebu**. Prava i uvjete provjeri na https://docs.libretro.com/library/snes9x/ i https://github.com/snes9xgit/snes9x . U slučaju daljnjeg dijeljenja binarne aplikacije pridržavaj se odgovarajućih licencnih obavijesti.
- *Choplifter* je naziv tuđe igre i vlasništvo pripada nositeljima odgovarajućih prava; ovaj projekt nije službeno izdanje ni odobrenje nositelja prava.
- Izvorni kod Android omotača u ovom arhivu: MIT licenca (`LICENSE`); licenca se **ne** odnosi na Snes9x ili na originalnu igru.

## Provjere provedene prije slanja

- C++ JNI kod: `clang++ -fsyntax-only` bez prijavljenih pogrešaka (uz minimalan lokalni Android bitmap stub).
- Java IPS patch: kompajliran i potvrđeno da originalni Choplifter ROM + IPS daje identičnu `.smc` datoteku kao naš ranije testiran MINIGUN ROM (CRC32 `07DFFA28`).
- **Nije izvršen Gradle build, instalacija APK-a, ni test stvarne emulacije na Androidu.** Ti koraci ostaju potrebni.
