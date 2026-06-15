# Pla d'integració del Tamagotchi de WiseWalk

Objectiu: convertir la mascota en el motor de motivació de l'app. El primer dia, un **ou misteriós apareix al pin de destinació** de la caminada; en arribar-hi es pot **reclamar** i desbloqueja una pantalla de **Tamagotchi clàssic**. A partir d'aleshores, **elements de cura (menjar, joguines...) apareixen repartits per les rutes**: es recullen caminant i es donen a la mascota.

Bucle de motivació: la mascota té gana i avorriment que baixen cada dia → per aconseguir menjar i joguines cal sortir a caminar → caminar puja l'XP i fa evolucionar la mascota.

---

## Principis d'arquitectura

- **Tota la lògica de joc viu a la capa web** (`wisewalk.html` + `localStorage`), com la resta de l'app. Sense backend: tot és local.
- **El natiu només pinta marcadors al mapa**: un estil d'ou per al pin de destinació i una capa nova de col·leccionables. Són 2 mètodes nous de pont i 1 overlay nou de Kotlin.
- **Estètica coherent**: carcassa de Tamagotchi clàssic (ovalada, 3 botons) feta amb CSS, pantalla tipus LCD amb la mascota en pixel art monocrom del color accent. Segueix el tema clar/fosc i els 4 colors accent existents.
- **Detecció de proximitat reutilitzada**: el mateix `setRouteUserLocation` que ja calcula snapping i arribada comprova la distància als col·leccionables (haversine ≤ 30 m).

---

## Fase 1 — Model de dades i lògica d'estat (només web)

Claus noves de `localStorage`:

- `wisewalk-pet`:
  ```json
  {
    "name": "Wisi",
    "stage": "egg|baby|child|adult",
    "bornAt": 0,
    "xp": 0,
    "hunger": 80,
    "happiness": 80,
    "lastTickAt": 0
  }
  ```
- `wisewalk-pet-inventory`: `[{ "type": "apple", "qty": 2 }, ...]`

Catàleg d'elements (constant JS):

| Tipus | Efecte | Pes d'aparició |
|---|---|---|
| `apple` (poma) | fam +20 | 45 % |
| `cookie` (galeta) | fam +10, felicitat +5 | 25 % |
| `ball` (pilota) | felicitat +15 | 20 % |
| `star` (estrella) | XP +10 | 10 % |

Funcions pures: `getPet()`, `savePet()`, `applyPetDecay()` (calcula la baixada des de `lastTickAt` en obrir l'app: fam −15/dia, felicitat −10/dia, amb sostre de 3 dies perquè una absència llarga no sigui un càstig), `feedPet(type)`, `playWithPet(type)`, `addPetXp(n)`, `getInventory()`, `addItem(type)`, `consumeItem(type)`.

Evolució per XP: ou (en reclamar) → cria (50) → jove (150) → adult (400). Si la fam arriba a 0 durant 2 dies, la mascota «se'n va d'excursió» i torna automàticament amb la següent caminada completada.

### Mort, cementiri i renaixement (vegeu també la Fase 6)

Si fa **3 dies o més que no s'obre l'app** (`PET_DEATH_AFTER_MS`, comprovat a `applyPetDecay` via el temps transcorregut des de `lastTickAt`), la mascota **mor**: es genera una **làpida** al cementiri (`wisewalk-cemetery`) amb el seu retrat, dates de naixement i mort, quilòmetres caminats i kcal cremades durant la seva vida, i s'esborra `wisewalk-pet`. La propera ruta torna a mostrar un **ou** per reclamar i així neix una mascota nova.

**✅ Comprovació per a l'usuari**: des de la consola de debug, crear mascota, alimentar-la i verificar persistència i decaïment manipulant `lastTickAt`.

## Fase 2 — L'ou al pin de destinació i la reclamació

- **Kotlin**: `PulsingMarkerOverlay` guanya un mode (`flag` per defecte, `egg`). El mode ou dibuixa un ou vectorial (oval blanc amb taques del color accent) amb les mateixes ondes i animació de caiguda actuals.
- **Pont nou**: `setDestinationMarkerStyle(style: String)` cridat des de JS just després de `drawRoute`.
- **JS**: si `getPet()` no existeix, en seleccionar una ruta el pin passa a mode ou, i en completar l'anada el botó canvia a **«Reclamar l'ou»**. En reclamar-lo:
  1. Es crea la mascota (`stage: "egg"`) i es mostra un **modal d'eclosió** (animació CSS de l'ou esquerdant-se).
  2. L'usuari posa nom a la mascota.
  3. Es desbloqueja l'accés a la pantalla Tamagotchi (icona nova a la capçalera).
- Si l'usuari abandona sense reclamar, l'ou reapareix a la següent ruta (no es perd mai l'oportunitat).

**✅ Comprovació per a l'usuari**: amb perfil nou, generar ruta → veure l'ou al pin del mapa → simular arribada (debug «Saltar al Destí») → reclamar → eclosió i pantalla nova.

## Fase 3 — Pantalla de Tamagotchi clàssic

- Pantalla nova `screen-pet` (s'amplia `showScreen`), accessible des d'una **icona de pota a la capçalera** amb punt de notificació quan la mascota necessita atenció, i des d'una **targeta d'estat a la pantalla principal** («La Wisi té gana — surt a caminar!»).
- Composició clàssica:
  - **Carcassa** ovalada CSS amb 3 «botons» físics decoratius/funcionals (Alimentar · Jugar · Estat).
  - **Pantalla LCD**: fons `--accent-light`, mascota en **pixel art** monocrom (`image-rendering: pixelated`, sprites de 16×16 inline en base64 o quadrícula CSS), 2-3 fotogrames per estat: content, gana, trist, dormint, més l'ou i les 3 etapes d'evolució.
  - **Barres d'estat**: fam, felicitat i XP fins a la pròxima evolució (reutilitzen `.goal-bar`).
  - **Inventari**: franja horitzontal amb els elements recollits i quantitats; tocar un element l'aplica amb una petita animació (el menjar «vola» cap a la mascota).
- Les accions consumeixen inventari; sense inventari, els botons queden apagats amb el missatge «Troba'n a les rutes!».

**✅ Comprovació per a l'usuari**: navegar a la pantalla, alimentar i jugar amb elements de l'inventari, veure barres i animacions, comprovar tema fosc i els 4 accents.

## Fase 4 — Col·leccionables a les rutes

- **JS (generació)**: en seleccionar una ruta es generen **1-2 elements** sobre punts reals de la polyline d'anada (entre el 25 % i el 85 % del recorregut, tipus segons els pesos del catàleg). Es guarden a la sessió de caminada (`walkSession.collectibles`) per sobreviure pantalla apagada.
- **Kotlin**: overlay nou `CollectibleOverlay` que pinta les icones (poma, galeta, pilota, estrella — dibuixades vectorialment com la resta de marcadors) amb una animació suau de flotació. Pont nou: `drawCollectibles(json)` i `removeCollectible(id)`.
- **JS (recollida)**: a `setRouteUserLocation`, si la distància a un element és ≤ 30 m → es reclama automàticament: banner breu a l'overlay de caminada («Has trobat una poma! 🍎 → inventari»), s'afegeix a l'inventari i es notifica el natiu perquè l'esborri del mapa amb una animació d'encongiment.
- En reclamar l'anada o la tornada es garanteix **+1 element extra** i **+10 XP** perquè cada caminada alimenti el bucle encara que no s'hagi passat per cap col·leccionable.

**✅ Comprovació per a l'usuari**: generar ruta → veure 1-2 icones sobre el traçat al mapa → en mode debug, saltar prop d'un element i veure'l reclamat, al banner i a l'inventari.

## Fase 5 — Equilibri, estat al dia a dia i polit

- Ajust de números (decaïment, XP, pesos) després de provar-ho uns dies.
- La targeta de la pantalla principal mostra l'estat resumit de la mascota i fa de recordatori de sortir a caminar.
- Micro-animacions: mascota que saluda en obrir l'app, celebració en evolucionar (confeti CSS).
- Opcional (fase posterior): notificació nativa diària si la fam és baixa, reutilitzant la infraestructura de notificacions de `StepTrackingService`.

## Fase 6 — Mort, cementiri, dissenys únics i còpia de seguretat

- **Mort per inactivitat**: si fa 3 dies que no s'obre l'app, `applyPetDecay()` crida `killPet()`, que crea una làpida i esborra la mascota.
- **Cementiri** (`screen-cemetery`, accessible des de Configuració i del modal de mort): graella de làpides, cadascuna amb el retrat de la mascota (pixel art amb el seu disseny), nom, dates de naixement i mort, km caminats i kcal cremades durant la seva vida (acumulats a `pet.kmWalked`/`pet.kcalBurned` a `notifyPetWalkCompleted`).
- **Renaixement**: en morir, la propera ruta torna a mostrar un ou. Cada ou pendent (`wisewalk-pending-egg`) i cada mascota tenen un **disseny únic autogenerat** (`generatePetDesign()`): color de cos, color de detall, patró de pell (taques/ratlles/panxa), color d'ulls i **color d'ou aleatori**. El color de l'ou es comunica al mapa natiu amb `setEggColors`.
- **Exportar / importar dades**: al final de Configuració, dos botons desen (`exportAppData` natiu o descàrrega web) i restauren totes les claus `wisewalk-*` de `localStorage` en un fitxer JSON. La importació fa servir `WebChromeClient.onShowFileChooser`.

---

## Resum de canvis per capa

| Capa | Canvis |
|---|---|
| `wisewalk.html` (JS) | Mòdul d'estat de mascota + inventari, modal d'eclosió, pantalla `screen-pet`, spawn i recollida de col·leccionables, integració amb el flux de reclamar, dissenys únics, mort + cementiri, exportació/importació de dades |
| `wisewalk.html` (CSS) | Carcassa Tamagotchi, LCD pixel art, barres, inventari, banner de recollida, làpides del cementiri i modal de mort |
| `MainActivity.kt` | Ponts: `setDestinationMarkerStyle`, `drawCollectibles`, `removeCollectible`, `setEggColors`, `exportAppData` + `onShowFileChooser` per a la importació |
| `PulsingMarkerOverlay.kt` | Mode «ou» amb color de closca/taques configurable |
| `CollectibleOverlay.kt` (nou) | Icones flotants d'elements sobre la ruta |

## Riscos i decisions obertes

- **Mida del fitxer únic**: `wisewalk.html` ja és gran; el mòdul de joc s'hi afegirà com a secció delimitada i, si creix més, es valorarà separar-lo en un asset JS propi.
- **Art**: començar amb sprites simples (16×16, 2-3 fotogrames); es poden refinar després sense tocar la lògica.
- **Una sola mascota** per usuari a la v1 (sense col·lecció d'espècies); el model de dades ho deixa obert per al futur.

## Ordre d'implementació proposat

1. Fase 1 (estat) + Fase 3 (pantalla) amb mascota creada per debug — el Tamagotchi es pot jugar sense tocar el mapa.
2. Fase 2 (ou al pin + reclamació) — connecta el primer dia.
3. Fase 4 (col·leccionables a ruta) — tanca el bucle de joc.
4. Fase 5 (equilibri i polit).
