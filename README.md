# Mine at Disco

A Fabric mod for Minecraft Java 26.3 that plays your own music and syncs the game to its beat.

While a song plays:

- A square around the crosshair closes on every beat, and four pips below it count the bar.
- The hotbar hops and particles swell on each beat, hardest on the first beat of the bar.
- The camera breathes in and out with the bar while you move, and rests when you stand still.
- Mobs and players dance like plushies, all in sync: a hop every two beats that lands on beats 1 and 3, a squash on each landing and a sway of the hips across the bar.
- Trees shed leaves when a bar opens.
- Mining hits land on every eighth note with a burst of chips, louder and bigger on the beat. Blocks finish breaking on the beat, their cracks ramp with it, and a placed block puffs on the next sixteenth.
- Every beat runs a velocity ramp: visuals slow down for a moment and then whip forward into the next beat, like a velocity edit. Chips hang in the air, then fly.
- Other world sounds wait for the song's grid: footsteps on eighth notes, the rest on the next sixteenth.
- Every effect grows when the song is loud and calms down in quiet parts.

## Use

1. Install Fabric Loader and Fabric API for Minecraft 26.3, then add the mod jar to `mods`.
2. Put your songs in the `disco` folder inside the game folder. The mod creates it on first launch. Songs must be Ogg Vorbis, so convert other formats first: `ffmpeg -i song.mp3 song.ogg`.
3. In a world, run `/disco play <song>`. Tab lists the songs it found.

`/disco stop` stops the music.

If the square closes before you hear the beat, which is common with Bluetooth headphones, delay it with `/disco offset <ms>`. A negative value makes it earlier.

## Build

Run `./gradlew build` with JDK 25. The jar lands in `build/libs`.

The beat tracker has a self-check that needs no build:

```bash
java src/main/java/it/vitoesposito/mineatdisco/BeatTracker.java
```
