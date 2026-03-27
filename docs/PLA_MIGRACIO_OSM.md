# Pla de Migració: De Google Maps + ORS a Osmdroid + OSRM

**Objectiu d'aquest document:** Guiar l'agent de codificació (Claude Code) per eliminar la dependència de Google Maps (SDK natiu) i d'OpenRouteService (JS API), passant a un ecosistema 100% lliure basat en OpenStreetMap.

---

## Fase 1: Neteja de la Clau API d'OpenRouteService (HTML/JS)
**Fitxer objectiu:** `app/src/main/assets/wisewalk.html`

**Prompt per a l'agent:**
> Llegeix l'HTML. Necessitem eliminar qualsevol rastre de la configuració de la clau API d'ORS.
> 1. Elimina el camp `<input type="password" id="api-key-config">` i la seva etiqueta/text d'ajuda associat a la pantalla de configuració (`#screen-config`).
> 2. A l'script JS, elimina la lectura i escriptura de `wisewalk-ors-apikey` a `localStorage`.
> 3. Elimina la funció `sanitizeApiKey(key)`.
> 4. A la funció `generateRandomRoute()`, elimina la validació que bloquejava la generació si no hi havia API key.
>
> ✅ **Validació:** Executa l'app o revisa l'HTML per assegurar-te que la pantalla de configuració ja no demana cap clau API per funcionar.

---

## Fase 2: Substitució del motor de rutes (De ORS a OSRM)
**Fitxer objectiu:** `app/src/main/assets/wisewalk.html`

**Prompt per a l'agent:**
> Reescriu la funció `fetchRouteWithOrs(start, end)`.
> 1. Canvia-li el nom a `fetchRouteWithOsrm(start, end)`.
> 2. Fes que faci un `fetch` a l'API pública d'OSRM per a caminants: `https://router.project-osrm.org/route/v1/foot/{start.lng},{start.lat};{end.lng},{end.lat}?overview=full&geometries=geojson`
> 3. El mètode ha de ser `GET` (no `POST` com a ORS) i no requereix headers ni body.
> 4. Adapta la gestió d'errors per comprovar si `response.ok` és false i retornar un missatge d'error genèric d'OSRM si falla.
> 5. Retorna directament l'objecte JSON obtingut: `return response.json();`.
>
> ✅ **Validació:** Confirma que el codi fa un `GET` directe a `router.project-osrm.org` sense enviar claus d'autenticació.

---

## Fase 3: Adaptació del parser de rutes i mètrics (HTML/JS)
**Fitxer objectiu:** `app/src/main/assets/wisewalk.html`

**Prompt per a l'agent:**
> OSRM retorna les dades amb una estructura diferent a ORS i **no inclou elevació**. Cal ajustar la funció `generateRouteFromLocation(start)`.
> 1. Canvia les crides de `fetchRouteWithOrs` a `fetchRouteWithOsrm`.
> 2. OSRM retorna les rutes a `data.routes`. L'objectiu a buscar no és `data.features[0]`, sinó `data.routes[0]`.
> 3. Per obtenir la distància (que OSRM retorna en metres directament a `data.routes[0].distance`), ajusta el càlcul de `totalDistanceKm`.
> 4. Per obtenir les coordenades del destí "snapped" (ajustades a la via), extreu-les de `data.routes[0].geometry.coordinates` (agafant l'últim element de l'array com a destí final).
> 5. A la funció `generateRandomRoute()`, estableix `ascent` i `descent` a `0`, ja que OSRM no dona aquesta dada.
> 6. Oculta visualment a l'HTML l'element de la UI de l'elevació (`#route-elevation` i el seu `route-detail-label` associat) aplicant-li un `display: none` o eliminant-lo.
>
> ✅ **Validació:** Verifica que l'app web és capaç de generar una ruta aleatòria i calcular-ne les calories (assumint desnivell 0).

---

## Fase 4: Migració Nativa de Google Maps a Osmdroid (Android)
**Fitxers objectiu:** `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/layout/activity_main.xml`, `app/src/main/java/com/wisewalk/app/MainActivity.kt`

**Prompt per a l'agent:**
> Fes la substitució completa de Google Maps per Osmdroid al codi natiu.
> 1. A `build.gradle.kts`, elimina `play-services-maps` i afegeix `implementation("org.osmdroid:osmdroid-android:6.1.18")`. Elimina qualsevol referència a `MAPS_API_KEY`.
> 2. A l'`AndroidManifest.xml`, elimina el `<meta-data>` de `com.google.android.geo.API_KEY`.
> 3. A `activity_main.xml`, substitueix el `FragmentContainerView` (Google Maps) per un `org.osmdroid.views.MapView` amb ID `mapView`.
> 4. A `MainActivity.kt`:
>    - Elimina `OnMapReadyCallback` i la variable `mMap`.
>    - Al mètode `onCreate`, inicialitza Osmdroid abans de carregar el layout: `Configuration.getInstance().load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))` i configura el `userAgentValue`.
>    - Crea una nova funció `initMap()` per carregar `mapView`, assignar-li el `TileSourceFactory.MAPNIK` i el multitàctil.
>    - Reescriu el mètode `drawRoute(coordinatesJson: String)` perquè parsegi el GeoJSON en una llista de `GeoPoint` d'Osmdroid i els afegeixi com a `Polyline` als overlays del mapa, netejant els previs. Finalment que faci `zoomToBoundingBox`.
>    - Reescriu `animateCameraForWalkMode` per utilitzar `map.controller.animateTo()` i `map.mapOrientation` per girar el mapa amb el `bearing`.
>    - Substitueix les importacions antigues de Google Maps per les equivalents d'Osmdroid (`GeoPoint`, `MapView`, `Polyline`, etc.).
>
> ✅ **Validació:** Compila l'aplicació (`./gradlew assembleDebug`). No hi hauria d'haver cap error d'importació de Google Maps i el mapa que es mostra hauria de ser l'estàndard d'OpenStreetMap.
