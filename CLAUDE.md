# Mine at Disco

A client-side Fabric mod for Minecraft Java 26.3, inspired by Dead as Disco. The player picks a song and the whole game moves to its beat: world sounds, mining, mobs, camera, HUD, particles, trees.

## Design rules

These come from the owner and decide every feature.

- The music touches the whole game, not only combat. Mining, building, walking and the world itself react.
- Never worse than vanilla. Player input is never delayed and the tick rate is never changed. Only feedback moves to the grid: sounds, particles, animations.
- Spectacle first: it should feel like a live TikTok edit and be worth clipping.
- Effects scale with the song. Big in loud parts, calm in breaks (`drive()` goes from 0.25 to 1.25).

Tuning the owner asked for, in order:

1. The first effects were too subtle, so everything got stronger.
2. A per-beat FOV punch gave headaches on a still view. The camera now breathes on a smooth curve over two beats and fades out when the player stops moving or looking around.
3. Mobs dance like the plushies in a short the owner shared, all in sync. At full size it was far too much, so it runs at 30%: one hop every two beats that lands on beats 1 and 3, a squash on landing, a sway of the hips across the bar.
4. The bar must start on the real "one" of the song. The tracker picks the heaviest beat of the eight-beat phrase from the bass, and the gold pip under the crosshair shows it.
5. Mining must sound clearly in time, so the mod plays its own hit on every eighth note instead of vanilla's 200 ms timer.

## Code map

- `BeatTracker.java` finds beats (onset envelope, tempo by autocorrelation, dynamic programming after Ellis 2007), shifts them so beat 0 opens a phrase, and measures each beat's loudness. `main` is a self-check with a synthetic click track.
- `MineAtDisco.java` holds everything else: `/disco play|stop|offset`, playback on the mod's own OpenAL source, the audio clock, the sound grid, mining, breaking and placing effects, leaves, the HUD.
- `mixin/SoundEngineMixin` holds world sounds until the next grid line and calls `frame()` once per frame.
- `mixin/ClientLevelMixin` takes over the mining hit effect and the block break burst.
- `mixin/LivingEntityRendererMixin` is the mob and player dance. `mixin/CameraMixin` is the FOV breath. `mixin/SingleQuadParticleMixin` swells particles. `mixin/FallingLeavesInvoker` sheds leaves.
- User-facing text lives in `assets/mineatdisco/lang/en_us.json`. Never hardcode a string the player sees.

## Timing

- `played()` is the audio position: OpenAL's sample offset, smoothed with the system clock because OpenAL updates it about every 20 ms.
- Sounds follow the audio position. Visuals follow `played() - offset`, which `/disco offset` calibrates for output latency.
- `beat()` counts from the first beat of a phrase: `beat % 4` is the position in the bar, `beat % 8` in the phrase.
- Grid: footsteps, mining and breaking fall on eighth notes, other world sounds on the next sixteenth. A sound in the first 20% of a grid step plays at once.

## Working method

- Read the game's API from the installed client jar with `javap` before writing a mixin. Since 26.x the jar is not obfuscated. Fabric API jars come from `maven.fabricmc.net`.
- Build with JDK 25: `export JAVA_HOME=$HOME/.jdks/jdk-25.0.5+8 && ./gradlew build`.
- Run the self-check after touching the tracker: `java src/main/java/it/vitoesposito/mineatdisco/BeatTracker.java`. To check a real song, pass raw PCM as its doc comment shows.
- Test in the real game, not `runClient` under WSL. Prism Launcher lives in `C:\Games\Minecraft` with a separate instance `mine-at-disco`. Leave the owner's `26.3` instance alone.
  - Copy `build/libs/mine-at-disco-0.1.0.jar` to `instances/mine-at-disco/minecraft/mods/`. The game must be closed to replace it.
  - Launch with `/mnt/c/Games/Minecraft/prismlauncher.exe --launch mine-at-disco`.
  - Watch `instances/mine-at-disco/minecraft/logs/latest.log` for mixin errors and `/disco` chat lines.
- Only the owner can hear and see the result. After each change, say what to try and ask how it feels, then tune.
- Songs go in `disco/` inside the game folder as Ogg Vorbis (`ffmpeg -i song.mp3 song.ogg`). `music/` in this repo is git-ignored because the songs are copyrighted.

## Conventions

- Write the simplest code that works. No abstractions nobody asked for. Mark a known shortcut with a `ponytail:` comment that names its limit and the upgrade path. Non-trivial logic leaves one runnable check.
- Code, comments, docs and in-game text are in English. Commits are one-line Conventional Commits in Italian, cut by topic, with no body and no Co-Authored-By trailer.
- Every piece of prose (README, strings, commits, this file) goes through the humanizer skill first: short and plain.
- Push to GitHub only when the owner says so.

## Open ideas

- A Flow meter that lights D-I-S-C-O as the player acts on the beat.
- Leaves that sway on the beat. This needs a terrain shader, and Sodium needs its own.
- A song picker screen, tap-to-calibrate, and a x2 or /2 fix for songs whose tempo reads double or half.
- Combat: hits that land on the beat, mob attacks on the beat, a perfect parry.
- A clip mode for vertical video, an end-of-song card, playing with friends.
- A license for the repo.

Known limits are marked `ponytail:` in the code: the tracker assumes 4/4 with a fixed phrase phase, tempo folds into 90 to 180 BPM, and every world sound is held for the grid, not only the player's.
