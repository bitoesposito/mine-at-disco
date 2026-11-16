# Mine at Disco

A Fabric mod for Minecraft Java 26.3 that plays your own music and syncs the game to its beat.

While a song plays:

- Four brackets fly in and land around the crosshair on every beat, gold before beat 1 and red with longer arms when your next full hit will be ready. Four pips below count the bar.
- The hotbar and the status bars bounce on the kicks the song really plays, in a wave from left to right, and rest when the drums do.
- While you move, the view kicks a little wider on beats 1 and 3, like a burst of speed. It rests when you stand still and follows the FOV Effects slider.
- Mobs and players dance like plushies, all in sync: a hop every two beats that lands on beats 1 and 3, a squash on each landing and a sway of the hips across the bar.
- Trees shed leaves when a bar opens.
- Mining hits land on every eighth note with a spray of chips off the block, louder and bigger on the beat. Broken blocks burst in a ring of chips on the next eighth note, with colored sparks on beats 1 and 3. Their cracks ramp with the beat, and a placed block kicks up dust on the next sixteenth.
- Combat snaps to the song: your hits flash and burst on the eighth note (bigger when you strike on the beat), melee mobs cue on the off-beat and strike on the beat, creepers detonate on the beat, and raising your shield right before a hit lands a perfect parry.
- Every beat runs a velocity ramp: animations, particles and cracks slow down for a moment and then whip forward into the next beat, like a velocity edit. Chips hang in the air, then fly.
- The first block you mine breaks as fast as in vanilla. Holding click across blocks locks the chain onto the beat and drifts it toward strong beats, so a chain of slow blocks settles onto beat 1 without running much slower than vanilla. Breaking, placing or hitting on the beat answers with sparks around the crosshair.
- When the band comes back after a break, a rainbow ring rolls out from you, fireworks go up and the trees let go.
- Mobs flash on the eighth note when hit and fall on the next beat when killed.
- Eating and drinking bite on every eighth note, harder on the beat. Throws and shots land on eighth notes, and snowballs, eggs and splash potions burst on the grid with sparks.
- Other world sounds wait for the song's grid: footsteps and combat on eighth notes, the rest on the next sixteenth.
- Every effect grows when the song is loud and calms down in quiet parts. Extra particles follow your Particles setting.

## Install

You need Minecraft Java 26.3, Fabric Loader 0.20.1 or newer and Fabric API. Put the mod jar from the [releases page](https://github.com/bitoesposito/mine-at-disco/releases) in your `mods` folder.

## Use

Press `M` in a world (or run `/disco`) to open the menu. The game keeps running and the middle of the screen stays clear, so you can watch the beat while you set things up. Press `M` or `Esc` to close it.

Your music sources are on the left:

- `YouTube` searches YouTube and saves the song you pick into your songs folder, then plays it.
- `System audio` follows whatever Windows plays, such as Spotify or a browser.
- Below it are your songs. The chest opens the songs folder and the barrier stops the music.

The songs folder is `disco` inside the game folder. The mod creates it on first launch. Songs must be Ogg Vorbis, so convert other formats first: `ffmpeg -i song.mp3 song.ogg`. You can also run `/disco play <song>` and `/disco stop`.

If Music or Master volume is at 0% when a world opens, the mod asks you to turn it up, since songs play on the Music volume.

### YouTube

Searching YouTube needs three free programs: yt-dlp, FFmpeg and Deno. If they are missing, the search screen offers to copy this command; run it in a terminal and search again without restarting the game:

```bash
winget install yt-dlp.yt-dlp Gyan.FFmpeg DenoLand.Deno
```

You can also put `yt-dlp` and `ffmpeg` in `disco/tools`. Videos longer than 12 minutes are left out, since a mix or a stream doesn't fit in memory once decoded. Download only music you have the right to use: YouTube's terms don't allow downloads.

### System audio

`System audio` (or `/disco live`) is experimental and needs Windows, so the mod asks before turning it on. It listens to what Windows plays, finds the beat in about five seconds and keeps following it, predicting the next beats so the game stays on time. It can drift or lose the beat; local songs track best, since the mod analyses the whole song before it starts.

### Timing

If a song's tempo reads half or double, click `BPM /2` or `BPM x2` in the menu (or run `/disco half` or `/disco x2`).

The mod reads your audio device's latency from OpenAL and keeps the visuals on what you hear, so most setups need no calibration. If the brackets still land before you hear the beat, which is common with Bluetooth headphones, tap `B` while playing (or `Space` in the menu) in time with the music, or use `-10 ms` and `+10 ms` (hold Shift for 1 ms steps). `/disco offset <ms>` sets the delay directly; a negative value makes it earlier. After you hit mobs or place blocks on the beat for a while, the menu suggests an offset from your own timing.

### Accessibility

The Distortion Effects slider in Minecraft's accessibility options scales the velocity ramp, the HUD bounce and the dance, down to none at 0%. Hide Lightning Flashes turns off the HUD sparks and the fireworks on drops. High Contrast gives the beat cue a darker edge.

On a multiplayer server the mod only moves visuals and sounds. Mining timing, mob timing and parries need a world you play yourself.

## Build

Run `./gradlew build` with JDK 25. The jar lands in `build/libs`.

Self-checks that need no build:

```bash
java src/main/java/it/vitoesposito/mineatdisco/BeatTracker.java
java src/main/java/it/vitoesposito/mineatdisco/AudioClock.java
java src/main/java/it/vitoesposito/mineatdisco/YouTube.java
```

## License

MIT, see [LICENSE](LICENSE).
