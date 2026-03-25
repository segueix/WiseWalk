# WiseWalk Native Maps Migration Plan

Aquest document defineix l'ordre d'implementació “prompt per prompt” i què es validarà a cada fase.

## Fase 1 — Base del mapa natiu
- Reestructurar `activity_main.xml` amb:
  - `FragmentContainerView` de mapa al fons.
  - `WebView` a sobre.
- Afegir `meta-data` de Google Maps API Key a `AndroidManifest.xml` amb placeholder.
- Actualitzar `MainActivity.kt` per:
  - Implementar `OnMapReadyCallback`.
  - Inicialitzar `SupportMapFragment`.
  - Guardar el mapa en `private var mMap: GoogleMap? = null`.
  - Activar `isMyLocationEnabled` amb comprovació de permisos.

## Fase 2 — Dibuix de ruta nativa (pont JS ↔ Kotlin)
- Afegir `drawRoute(coordinatesJson: String)` a `WiseWalkBridge`.
- Parsejar coordenades GeoJSON (`[longitude, latitude]`) i dibuixar `Polyline` verda gruixuda.
- Netejar mapa abans de redibuixar i fer `zoom bounds`.
- En `wisewalk.html`, enviar coordenades de la ruta cap a Kotlin després de generar-la.

## Fase 3 — GPS al Foreground Service + càmera intel·ligent
- Moure actualitzacions GPS freqüents al `StepTrackingService` amb `FusedLocationProviderClient`.
- Broadcast de lat/lng/bearing des del servei.
- `MainActivity`:
  - Escoltar broadcasts.
  - Enviar ubicació a web (`wiseWalkSetLocation`).
  - Animar càmera del mapa (zoom 18, tilt 45, bearing).
- Connectar `startWalkLocationUpdates` / `stopWalkLocationUpdates` per activar o aturar el mode GPS.

## Fase 4 — Interfície transparent flotant
- Garantir `WebView` transparent des de Kotlin.
- Afegir classe CSS `.map-mode` a `wisewalk.html` per fer transparent el fons i amagar blocs no necessaris.
- Activar `.map-mode` en iniciar navegació i desactivar-la en finalitzar-la.

## Criteri operatiu de cada resposta d'implementació
- Al final de cada resposta d’una fase implementada, imprimir literalment:
  - `✅ Comprovació per a l'usuari`
- Incloure els passos de validació corresponents a la fase.
