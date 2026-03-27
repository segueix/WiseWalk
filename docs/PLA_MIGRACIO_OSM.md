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

---

## Fase 5: Funció de mostreig i consulta d'elevació
**Fitxer objectiu:** `app/src/main/assets/wisewalk.html`

**Prompt per a l'agent:**
> A l'script JS de `wisewalk.html`, crea una nova funció asíncrona anomenada `fetchElevationGain(coordinates)`.
> Aquesta funció ha de fer el següent:
> 1. Rebre l'array de coordenades GeoJSON (format `[lng, lat]`) que retorna OSRM.
> 2. Com que Open-Meteo accepta un màxim de 100 punts per petició GET, aplica un mostreig a l'array per quedar-te amb un màxim de 90 punts equidistants de la ruta (calculant un `step = Math.max(1, Math.floor(coordinates.length / 90))`).
> 3. Extreu dos arrays: un amb les latituds i un altre amb les longituds d'aquests punts mostrejats.
> 4. Fes un `fetch` a l'API d'Open-Meteo amb el format: `https://api.open-meteo.com/v1/elevation?latitude=lat1,lat2...&longitude=lng1,lng2...`
> 5. Del JSON de resposta (`data.elevation`), calcula el desnivell positiu acumulat (`totalPositiveGainM`). Això es fa iterant sobre l'array d'elevacions i sumant la diferència només si el punt actual és més alt que l'anterior (`elevations[i] - elevations[i-1] > 0`).
> 6. Retorna el valor total del desnivell positiu. Si la petició falla, retorna `0` perquè la ruta es pugui continuar mostrant sense bloquejar l'app.
>
> ✅ **Validació:** Verifica visualment al codi que la funció construeix bé la URL amb paràmetres separats per comes i retorna un número.

---

## Fase 6: Integració de l'elevació al flux principal
**Fitxer objectiu:** `app/src/main/assets/wisewalk.html`

**Prompt per a l'agent:**
> Actualitza la funció `generateRandomRoute()` a `wisewalk.html` per integrar el nou càlcul d'elevació i restaurar la UI.
> 1. Just després d'obtenir la ruta correcta d'OSRM (`routeResult.data`), extreu l'array complet de coordenades de la geometria de la ruta (`routeResult.data.routes[0].geometry.coordinates`).
> 2. Crida la nova funció `fetchElevationGain` passant-li aquestes coordenades i guarda el resultat a la variable `totalPositiveGainM`.
> 3. Elimina qualsevol codi temporal de la "Fase 3" que forçava l'elevació a 0.
> 4. Assegura't que l'element HTML de l'elevació (`#route-elevation` i el seu contenidor) torna a ser visible (elimina el `display: none` si el vas afegir anteriorment).
> 5. Actualitza el text de `#route-elevation` amb el valor obtingut (`+${Math.round(totalPositiveGainM)} m`).
>
> ✅ **Validació:** Genera una ruta nova a l'aplicació. Hauries de veure de nou el desnivell a la interfície i el càlcul de calories s'hauria d'ajustar a aquest desnivell, mantenint l'app completament independent i sense claus API.
