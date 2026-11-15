# Mine at Disco

A Fabric mod for Minecraft Java 26.3 that plays your own music and syncs the game to its beat.

While a song plays:

- Four brackets fly in and land on the crosshair on every beat, gold before beat 1, and four pips below count the bar.
- The hotbar and the status bars bounce after each beat in a wave from left to right, hardest on beat 1.
- While you move, the view kicks a little wider on beats 1 and 3, like a burst of speed. It rests when you stand still and follows the FOV Effects slider.
- Mobs and players dance like plushies, all in sync: a hop every two beats that lands on beats 1 and 3, a squash on each landing and a sway of the hips across the bar.
- Trees shed leaves when a bar opens.
- Mining hits land on every eighth note with a spray of chips off the block, louder and bigger on the beat. Blocks finish breaking on the beat in a ring of chips, with colored sparks on beats 1 and 3. Their cracks ramp with the beat, and a placed block kicks up dust on the next sixteenth.
- Combat snaps to the song: your hits flash and burst on the eighth note (bigger when you strike on the beat), melee mobs cue on the off-beat and strike on the beat, creepers detonate on the beat, and raising your shield right before a hit lands a perfect parry.
- Every beat runs a velocity ramp: animations, particles and cracks slow down for a moment and then whip forward into the next beat, like a velocity edit. Chips hang in the air, then fly.
- Repeated mining drifts toward strong beats, so a chain of slow blocks settles onto beat 1. Breaking, placing or hitting on the beat answers with sparks around the crosshair.
- Other world sounds wait for the song's grid: footsteps and combat on eighth notes, the rest on the next sixteenth.
- Every effect grows when the song is loud and calms down in quiet parts. Extra particles follow your Particles setting.

## Use

1. Install Fabric Loader and Fabric API for Minecraft 26.3, then add the mod jar to `mods`.
2. Put your songs in the `disco` folder inside the game folder. The mod creates it on first launch. Songs must be Ogg Vorbis, so convert other formats first: `ffmpeg -i song.mp3 song.ogg`.
3. In a world, press `M` (or run `/disco`) to open the song menu, or run `/disco play <song>`.

`/disco stop` stops the music.

If a song's tempo reads half or double, click `/2` or `x2` in the menu (or run `/disco half` or `/disco x2`).

If the brackets land before you hear the beat, which is common with Bluetooth headphones, tap `B` while playing (or `Space` inside the `M` menu) in time with the music, or set the delay with `/disco offset <ms>`. A negative value makes it earlier.

On a multiplayer server the mod only moves visuals and sounds. Mining timing, mob timing and parries need a world you play yourself.

## Build

Run `./gradlew build` with JDK 25. The jar lands in `build/libs`.

The beat tracker has a self-check that needs no build:

```bash
java src/main/java/it/vitoesposito/mineatdisco/BeatTracker.java
```
