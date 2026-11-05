# HYDE TV

[ 🇷🇺 Русский (README.md) ](README.md) | [ 🇬🇧 English (README.en.md) ](README.en.md)

**Stock Google TV with ads ➔ HYDE TV (Vision and Graphite, 60 FPS)**  
![Before and After: a curtain moves from the stock Google TV screen with ads to the HYDE TV home screen, first in the Vision style, then in the Graphite style](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@8c457cc/docs/screenshots/before-after.gif)  
Drag the curtain yourself and switch the variant (Vision, Graphite, light glass): [open the comparison](https://artemhyde.github.io/artemhyde_TV/before-after.html), or on your computer `bash tools/before-after.sh` (Windows: `powershell -ExecutionPolicy Bypass -File tools\windows\before-after.ps1`).

**What it is**  
A stock Google TV with ads on the home screen becomes a clean TV: its own home screen, quick settings on a remote button, night mode, sound and picture controls over a film, music. Your Claude Code installs and sets it all up: it asks one question at a time, you answer and press "Allow" on the TV. No root, the firmware stays stock. In packages and folders the project keeps its technical name, glasstv (`app.glasstv.*`).

**Why this beats a typical TV "build"**  
A typical "build" for a TV is a bundle of apps from a forum or a Telegram channel, sometimes a reflashed box: free cinemas, pirate sources, someone else's launcher. It works while the source lives, updates by hand, and nobody knows what is inside.  
Here it is different:
- It goes onto your TV as is: no reflashing and no root. Every change is logged and rolled back with one command.
- An AI installer (Claude Code) does the setup: in your language, step by step, asks what you need and never acts silently.
- The code is open. Third-party apps are downloaded only from their official pages and checked by signature.
- Not a pile of icons but one system: its own home screen (clock, weather, rates, "continue watching", music), night backlight on a schedule, even sound, a menu on a remote button, the Volna music player.
- With your consent, ads, telemetry and unneeded TCL services are switched off.
- The project installs no pirate sources.
- Animations are measured on the TV itself: 60 frames per second on weak hardware.

Honest downsides: so far tested on one TV (TCL 65C6K, Android 14), you need a computer for the setup, and the browser and the Volna player are built on your machine, which takes a while.

The apps on the TV are in Russian. The setup wizard speaks your language: write to Claude in English and it answers in English.

**Two home screen styles**  
Switched by the "Style" pill in the gear-button strip, instantly, no restart.  
"Graphite": clock and weather at the edges, the photo under the row blurred and dimmed, glass tiles from the left edge, "Continue" and "Volna" capsules under the row.  
![Graphite style](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/home.png?v=0411b)

"Vision": everything centred, like the iPhone lock screen. The date above a large clock, the photo stays sharp, small capsules under the clock, a row of glass spheres, and the selected sphere always sits in the middle of the screen. The focused sphere grows with a soft bounce and glows in its app's colour, its neighbours make room, a light glint runs across the glass, and OK presses it in like a real button.  
![Vision style](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/home-vision.png?v=0511)

Each style has its own lock screen (after a minute without key presses):  
![Lock screen, Graphite](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/lock-graphite.png?v=0411b)  
![Lock screen, Vision](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/lock-vision.png?v=0511)

Light "Obyom" theme is available too (the wizard asks during setup):  
![Light Obyom theme](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/theme-volume.png?v=0411b)

**Which apps get installed and why**  
Only what you choose gets installed. The only required third-party app is Key Mapper, and only if you want your own remote buttons.

| App | What it is | Why it is here | Needed? |
|---|---|---|---|
| "Dom" (ours) | home screen | replaces the Google TV screen with ads: photo, clock, weather, your apps | optional, you can keep yours |
| "Storozh" (ours) | background service | night backlight, back to home on power-off, network debugging, restarts Key Mapper | needed for Sound, Picture and night |
| "Zvuk", "Kartinka", "Night mode" (ours) | strips over the film | sound and picture modes, brightness and sleep timer, the film keeps playing | optional; Sound needs Dolby, Picture needs TCL |
| "Keyboard" (ours) | keyboard for the remote | digits always in place, capitals and erase by holding | optional |
| "Volna" (ours) and a custom Aura Music build | music from YouTube Music | "My wave" and "New for you", lyrics, likes go to your account | optional, built on your computer |
| Key Mapper 4.6.2 foss | remote buttons | without root it is the only way to give special buttons your own actions; the version is pinned, the mapping was tested on it | if you map buttons |
| SmartTube | YouTube for TV | easier with a remote than the stock app, sign-in with a code on your phone | optional |
| Lampa | film and series catalogue | descriptions, ratings and seasons, "Films" and "Series" tiles; details in [docs/lampa.md](docs/lampa.md) | optional |
| Just Player | video player | hardware playback of heavy 4K HDR files, opens video from other apps | optional |
| TV Bro | browser for the remote | sites with players, address entry with a remote; our patched build or the regular one from Google Play | optional |
| Material Files | files | video and photos from a USB stick | optional |
| NewPipe | YouTube in a window | a small window over other apps | optional |
| Twitch | streams | official, from Google Play only | optional |
| Aurora Store | Google Play catalogue without sign-in | install apps without a Google account (grey zone of Google terms) | optional |

**Accounts and sign-in**  
Only you type passwords, the wizard never sees or types them. Signing in is optional everywhere.
- YouTube (SmartTube): without sign-in, search and playback work. With it you get subscriptions, history and recommendations. In SmartTube choose "Sign in", a code appears on screen, enter it on your phone.
- Google Play: needed only for Twitch and the regular TV Bro, you sign in on the TV itself.
- "Volna" music (YouTube Music), three options:
  - No account: "Volna" plays. "New for you" uses world charts and genres right away, "My wave" learns from your likes and history on the TV.
  - A regular free Google account: sign in to the "Aura (Volna)" player from "All apps" (settings, account, sign in to YouTube Music). Selections follow your YouTube Music history, likes and "Not for me" go to your account.
  - YouTube Premium: the same. Premium is not required for "Volna". YouTube Music gives subscribers higher audio quality (up to 256 kbps instead of 128); whether it reaches you through Aura, we have not checked.
  - A second person with their own taste: a channel inside the same account with the same name as in "Volna". Its likes never reach the main profile. Without an account the second taste lives on the TV only.

**How to install**
1. Install Claude Code (claude.com/claude-code).
2. Download this repository (Code button, Download ZIP) and unpack it.
3. Open the folder in Claude Code and write "let's start". The TV and the computer must be on the same home network.

You need: Google TV on Android 12 or newer, a computer with Windows, macOS or Linux, about 600 MB for tools, about 20 minutes (the browser with our patches takes longer to build).  
What happens: Claude asks its questions one at a time (theme, remote buttons, which apps, city for the weather), takes a "before" snapshot of your TV, records every change and builds a rollback script from those records, then builds the apps with your own signing key and installs them.

**Responsibility**  
You change your TV and its system at your own risk. This is an unofficial project: there is no guarantee it works on your model or after a firmware update. A snapshot is taken before any change, rollback is described in [docs/rollback.md](docs/rollback.md) (in Russian, like the rest of docs/; your Claude can read and explain them in your language).

**What it does**

"Dom" (Home): the home screen. A photo for the time of day, clock, weather, a verse, app tiles and an "All apps" screen. When nobody touches the TV it becomes a lock screen with the clock and the verse: the Android screensaver is switched off completely, so there is no second screen on top of it.  
![All apps](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/apps.png?v=0411b)


Pills strip on the remote's gear button: Night, Sound, Picture, Volna, Style, TCL panel. It appears over any app or film. A long press on the gear can open the TCL panel: this does not work on every TV, and if it does not, the TCL panel stays in the strip.  
![Pills strip](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/pills.png?v=0411b)

"Zvuk" (Sound): a glass strip over the film. Four modes (Cinema, Music and concerts, Night, Clean) and sliders: speech clarity, levelling of explosions and whispers, bass depth, detail. You change it by ear, the film does not stop.  
![Sound over a film](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/sound.png?v=0411b)

"Kartinka" (Picture): the same kind of strip. Four modes (Cinema Evening, Daylight Sharp, Maximum HDR, Soft for the eyes), "Auto" and sliders: panel backlight, black depth, HDR peak, warm tint.  
![Picture](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/picture.png?v=0411b)

"Night mode" in two stages. First press: a small plate at the bottom of the screen with one brightness slider. The slider shows the real panel backlight one to one: from zero, left goes straight into the minus, right goes 1, 2, 3. Left of zero is the red zone down to −100 %: a veil, and below −50 a second layer over it, so the difference is visible all the way. Second press of the same button (or Down and OK on "Sleep timer"): the plate smoothly grows into the full menu with the sleep timer and an "Off" button.  
![Night mode: the plate](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/night.png?v=0411b)  
![Night mode: the full menu](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/night-full.png?v=0411b)

"Storozh" (Guard): a background service. It changes the backlight exactly twice a day, at 22:00 and at 7:00, and does not argue with you: touch the brightness yourself and it leaves it alone until the next morning. Optionally it levels the volume; it returns the TV to the home screen on power-off, keeps wireless debugging on and watches Key Mapper. It can also look after a local server of another app (address and package are set by a command): if the server is gone, it starts the app again and brings back the screen you were on.  
![Guard](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/guard.png?v=0411b)

"Volna" (optional): music through a custom build of Aura Music (patches and the build script are in `aura/`, there is no APK in the repository), "My wave" and "New for you" selections, lyrics. A track starts by its exact id, and the close button shuts the music down for good: no button and no TV restart brings it back. If you signed in to the player yourself, the waves are built from your account, and a like or a "Not for me" goes there. The second taste has its own channel of the same account with the same name, and its likes never reach the main profile. High-resolution covers, a player background in the colour of the cover, and the remote goes where the arrow points: Up from the buttons to the seek bar, Right into the list row at the same height, Left from the list back to the buttons, Back to the tabs, and a hint under every element says what OK will do. "My wave" is about one third your own tracks and the rest in the same mood; "Not for me" removes that whole direction for the rest of the evening. A removed video is replaced with the same song from YouTube Music.  
![Volna](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/volna.png?v=0411b)

"Keyboard" for the remote: digits always in their own row, above them the signs the field needs (domains in one key for mail, password signs for passwords), capitals and erasing by holding a key, nothing resets by itself. No permissions and no internet, one command to go back.

TV Bro browser with our patches (optional, built on your computer): site players, input suggestions, a separate panel for editing the address with a yellow caret and a "Cut the link" button, a flat dark look without extra frames. The screenshot shows the start page with Russian sites; this build has a neutral set there (Bing, Reddit, Wikipedia, IMDb, BBC News, DuckDuckGo).  
![Browser](https://cdn.jsdelivr.net/gh/artemhyde/artemhyde_TV@main/docs/screenshots/browser.png?v=0411b)

Remote buttons: mapped through Key Mapper, any special button can open the app you want.

**Compatibility**

| TV | Status |
|---|---|
| TCL 65C6K, Android TV OS 14 | tested |
| Other TCL Google TVs with Android 12 and newer | expected to work, needs a probe (the wizard runs it) |
| Other Google TVs with Android 12 and newer | partly: no "Picture", backlight or Dolby; "Sound" only if the probe passes |
| Android TV 11 and older, Fire TV, Tizen, webOS | not supported |

More on what is tied to TCL: [docs/tcl.md](docs/tcl.md).

**Safety and rollback**
- Before working, the wizard takes a snapshot of your TV (it contains the serial number and addresses, stays on your computer only and never goes into git).
- Every change is recorded, and a rollback script is built from the records. It appears after the first changes (before them there is nothing to roll back): the wizard shows it to you and checks it with a dry run. What is and is not rolled back: [docs/rollback.md](docs/rollback.md).
- System packages are only disabled, not removed. There is a "do not touch" list (data/tcl-never-touch.txt).
- Everyone builds the apps with their own key. The repository holds no ready APKs and no other people's keys.
- Third-party apps are downloaded from their authors' links and checked against the signature fingerprint.
- The wizard never enters passwords, never accepts terms for you, and sends nothing out on its own, except the city name to Open-Meteo and downloads from the list. The apps on the TV then reach services on their own: weather by your city's coordinates (Open-Meteo), photos (Wikimedia), optionally exchange rates, YouTube Music and lyrics. The full list is in [docs/architecture.md](docs/architecture.md).
- The wireless debugging you enable on the TV only accepts computers you confirmed, but any device on the home network can send a request. Never press "Allow" without looking. Guard can show notes from a phone (port 8765 on the home network, no password): the wizard checks whether the port is listening and tells you plainly.
- CLAUDE.md is plain text that your Claude executes. Read it. Download the project only from this repository, and read other people's copies yourself.

**Honest limitations**
- It relies on undocumented TCL and Dolby firmware interfaces. A firmware update may break it or bring back what was disabled (the wizard will go through the list again). We have no connection to TCL or Dolby.
- The apps' interface is Russian only. The wizard in Claude Code speaks your language.
- Button mapping is done in Key Mapper step by step on the live screen and is tested only on version 4.6.2 foss. The "Send intent" form may not open on your TV: then every action goes through "Launch app", and the long press of the gear stays without the TCL panel.
- Volna reaches YouTube Music with undocumented requests. This is a grey area of YouTube's terms: optional and at your own risk. The sound is played by a build of the third-party Aura Music player under GPL-3.0; our patches do not change how it gets the stream.
- The light "Obyom" theme exists but is checked only on a home screen snapshot of one TV. The default is the dark "Graphite".
- The browser is built from patches to the TV Bro sources. Do not pass the built APK to other people: that would break the TV Bro license.
- Weather: Open-Meteo, free for non-commercial use only. National Bank of Belarus rates are enabled separately and are optional.
- Not tested on a live TV: building and installing on macOS and Linux, work on non-TCL TVs, importing a Key Mapper layout from a file, behaviour after a reboot.

**Other projects and licenses**  
We do not copy or distribute other people's apps, only links. The full list with licenses: [THIRD-PARTY.md](THIRD-PARTY.md).
- Key Mapper (GPL-3.0): remote buttons, github.com/keymapperorg/KeyMapper (formerly sds100/KeyMapper), version 4.6.2 foss is installed
- SmartTube (MIT): YouTube client, github.com/yuliskov/SmartTube
- Lampa: film catalogue, github.com/lampa-app/LAMPA (no license stated on the page, link only)
- TV Bro (own license): browser, github.com/truefedex/tv-bro, our changes are kept as patches
- Aura Music (GPL-3.0): the player for Volna, github.com/TeamAuraMusic/AuraMusic, our changes are patches in the `aura/` folder, which is under GPL-3.0
- Inter (SIL OFL 1.1): font, github.com/rsms/inter
- Wikimedia Commons: photos with author and license on screen (CC BY-SA, CC BY, CC0)
- Open-Meteo (CC BY 4.0): weather, open-meteo.com  
The project is not affiliated with TCL, Google or Dolby. Trademarks belong to their owners and are used only to name the apps.

**Documentation** (in Russian; ask your Claude to explain any of it in your language)
- [docs/guide.md](docs/guide.md): how to use it with the remote
- [docs/architecture.md](docs/architecture.md): how it all works
- [docs/tcl.md](docs/tcl.md): what is tied to TCL and what to do on other TVs
- [docs/rollback.md](docs/rollback.md): rollback
- [docs/lampa.md](docs/lampa.md): Lampa settings
- [docs/faq.md](docs/faq.md): frequent questions
- [docs/publish.md](docs/publish.md): how to publish your own copy on GitHub

**License**  
MIT, see [LICENSE](LICENSE). The Inter font comes separately under SIL OFL 1.1. Other projects are under their own licenses (THIRD-PARTY.md).
