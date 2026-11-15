# Mine at Disco

A Fabric mod for Minecraft Java 26.3 that plays your own music and syncs the game to its beat.

For now it plays a song, finds its beats, and draws a square around the crosshair that closes on every beat. Nothing else reacts to the music yet.

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
