# Mine at Disco

A Fabric mod for Minecraft Java 26.3 that plays your own music and syncs the game to its beat.

While a song plays:

- A square around the crosshair closes on every beat, and four pips below it count the bar.
- The camera punches in, the hotbar hops and particles swell on each beat, hardest on the first beat of the bar.
- Mobs nod and squash with the pulse.
- Trees shed leaves when a bar opens.
- World sounds wait for the song's grid. Mining and footsteps fall on eighth notes, other sounds on the next sixteenth.

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
