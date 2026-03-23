# Generar l'APK amb GitHub Actions

Aquest repositori inclou el workflow `.github/workflows/android-apk.yml` per compilar automàticament una APK *debug* de l'app Android.

## Què fa el workflow

- S'executa automàticament a cada `pull_request`.
- S'executa automàticament quan hi ha `push` a `main` o `master`.
- Es pot llançar manualment des de **Actions > Android APK > Run workflow**.
- Compila l'APK amb `./gradlew assembleDebug`.
- Publica el fitxer generat com a artefacte de GitHub Actions amb el nom **app-debug-apk**.

## Com descarregar l'APK

1. Ves a la pestanya **Actions** del repositori.
2. Obre l'execució del workflow **Android APK**.
3. A la secció **Artifacts**, descarrega **app-debug-apk**.
4. Dins del `.zip` trobaràs `app-debug.apk`.

## Requisits del repositori

No cal configurar secrets per generar la versió *debug*.

El workflow prepara:

- Java 17
- Android SDK
- Gradle

## Si més endavant vols una APK signada de release

Per una *release APK* instal·lable fora de l'entorn de desenvolupament, caldrà afegir:

- un keystore de signatura,
- secrets de GitHub amb les credencials,
- i una configuració de `signingConfigs` al mòdul `app`.

Aquest canvi actual deixa preparat el pipeline base per generar l'APK de prova des de GitHub Actions.
