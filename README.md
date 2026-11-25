# QR olvasó – Android telefon mint Bluetooth kódolvasó

A telefon kamerája leolvassa a **Micro QR** és a sima **QR** kódot, és a tartalmát
**Bluetooth-billentyűzetként begépeli** a notebookba, mint egy kézi vonalkódolvasó.
A Windowsra nem kell semmit telepíteni. Ahol a kurzor áll (Excel, Jegyzettömb, böngésző),
oda kerül a kód, utána Enter.

Követelmény: Android 9 vagy újabb.

## Funkciók

- **Folyamatos mód:** tartsd a kódot a keretbe, és magától beküldi. Ugyanazt a kódot csak akkor küldi
  újra, ha előtte kivetted a képből.
- **Ravasz mód:** nyomd és tartsd az OLVASÁS gombot vagy egy hangerőgombot. Egy lenyomás = egy kód.
- **Sípszó + zöld villanás** = elküldve. Narancs villanás = beolvasta, de nincs kapcsolat.
- **Nagyítás csúszka** és **koppintásos élességállítás** az apró Micro QR kódokhoz, valamint **lámpa**.
- **Beállítások:**
  - a notebook kiosztása: Magyar / Angol (US) / Alt-kód;
  - mi jöjjön a kód után: Enter / Tab / semmi.
- Figyeli a notebook Caps Lock állását, így a nagybetűk nem fordulnak meg.

## APK elkészítése

Ugyanúgy megy, mint a Prezenternél:

1. Hozz létre egy **új, üres** repót a github.com-on (pl. `qr-olvaso`), README nélkül.
2. Kattints az **uploading an existing file** linkre, és húzd be a kicsomagolt `QR-Olvaso` mappa
   **tartalmát**: `.github`, `app`, `build.gradle`, `settings.gradle`, `gradle.properties`, `README.md`.
   Utána **Commit changes**.
3. **Actions** fül → „APK készítése” → a zöld pipa után az **Artifacts** alól töltsd le a `QR-Olvaso-APK`-t.
4. Telepítsd a telefonra. Az app neve: **QR olvasó**, kamera ikonnal.

Ha az Actions alatt nem jelenik meg semmi, hozd létre kézzel a `.github/workflows/build.yml` fájlt
(**Add file → Create new file**), és másold bele a zipben lévő azonos nevű fájl tartalmát.

## Párosítás

- **Ha a Prezenter appal már párosítottad a notebookot**, nem kell újra párosítani. A két app
  ugyanolyan billentyűzetnek látszik. Egyszerre viszont csak az egyik működhet, ezért a másikat zárd be
  (az áttekintő nézetben húzd ki).
- **Új párosítás:** az appban **Új párosítás** gomb. A notebookon: **Beállítások → Bluetooth és eszközök →
  Eszköz hozzáadása → Bluetooth** → válaszd a telefont.

## Ha valami nem stimmel

- **Fel van cserélve az y és a z, vagy furcsa jelek jönnek:** a Beállításokban válaszd ki a notebook
  kiosztását. Ha semmi nem segít, használd az **Alt-kód** módot. Ez minden kiosztással működik,
  csak lassabb.
- **Nem olvassa az apró kódot:** húzd fel a nagyítást, tartsd a telefont 10–15 cm-re, koppints
  a képre az élességért, gyenge fényben kapcsold be a lámpát.
