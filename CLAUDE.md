# Mine at Disco

A client-side Fabric mod for Minecraft Java 26.3, inspired by Dead as Disco. The player picks a song and the whole game moves to its beat: world sounds, mining, mobs, camera, HUD, particles, trees.

## Design rules

These come from the owner and decide every feature.

- The music touches the whole game, not only combat. Mining, building, walking and the world itself react.
- Never worse than vanilla. Player input is never delayed and the tick rate is never changed. Only feedback moves to the grid: sounds, particles, animations.
- Spectacle first: it should feel like a live TikTok edit and be worth clipping.
- Effects scale with the song. Big in loud parts, calm in breaks (`drive()` goes from 0.25 to 1.25).
- Visuals follow the ramp. Every beat slows entity animations, particle motion, cracks and the HUD down and then speeds them up into the next beat like a velocity edit. The camera stays steady: bent on every beat it read as losing speed while running. Sounds and real positions keep their pace.
- Land on the satisfying parts. Repeated actions drift toward strong beats, and an action that lands on the beat pays off with a bigger burst, colored sparks and an answer on the HUD. Missing the beat costs nothing.
- Change game timing only in a world this game runs. On a server the mod moves only visuals and sounds, so anticheats have nothing to flag.

Tuning the owner asked for, in order:

1. The first effects were too subtle, so everything got stronger.
2. A per-beat FOV punch gave headaches on a still view. The camera now breathes on a smooth curve over two beats and fades out when the player stops moving or looking around.
3. Mobs dance like the plushies in a short the owner shared, all in sync. At full size it was far too much, so it runs at 30%: one hop every two beats that lands on beats 1 and 3, a squash on landing, a sway of the hips across the bar.
4. The bar must start on the real "one" of the song. The tracker picks the heaviest beat of the eight-beat phrase from the bass, and the gold pip under the crosshair shows it.
5. Mining must sound clearly in time, so the mod plays its own hit on every eighth note instead of vanilla's 200 ms timer.
6. The FOV breath came back down to 9% at full loudness: 15% felt too strong while moving.
7. Every beat runs a velocity ramp: the visual beat, particles, cracks and entity animations slow down after the beat, then whip forward and land on the next beat. The music never changes pace.
8. Mining snaps to the beat. `MultiPlayerGameModeMixin` folds vanilla's 5-tick cooldown between blocks into the block's time, drives the crack stages (`0..9`) with the ramped visual beat, and finishes breaking right on the beat so fast mining (such as Efficiency V on stone) breaks one block per beat without drifting or skipping beats.
9. The D-I-S-C-O flow meter and DISCO mode were tried and dropped: they looked flat and added nothing to the experience.
10. Combat snaps to the beat without delaying player input. Player attacks deal damage at once, while the mob's red flash, hurt or death sound and spark burst land on the next eighth note (bigger with a sweep flash when clicked on the beat). Melee mobs play a warning cue on the eighth note before the beat and strike on the beat, creepers hold at full swell until the beat, shields raise with no 5-tick delay, and raising a shield right before a hit lands a perfect parry that blasts the attacker back and ignores axe shield-disable.
11. Pressing `M` (or running `/disco` with no arguments) opens the in-world song picker and calibration screen without pausing the game or covering the beat cue around the crosshair. Pressing `B` in-game (or `Space` inside the menu, or `/disco tap`) taps in time with the music to calibrate audio offset on the fly. The menu and `/disco x2` / `/disco half` also nudge the offset by 10 ms or double/halve the beat grid when a song's tempo reads half or double.
12. While running and jumping the view seemed to lose speed every other beat. The FOV narrowed on beats 1 and 3 and followed the ramp, and every particle nearly froze on each beat. The camera now kicks a little wider on the steady beat and follows the FOV Effects slider, and particle motion no longer bends.
13. The particle effects looked poor: every particle in the world swelled on the beat, and the chips used vanilla's spawn, which randomizes speed and leaves them limp. The swell is gone, chips and bursts fly with exact velocities (`spawn()`), break bursts are no longer tripled, colored sparks mark breaks on beats 1 and 3, and extra particles follow the Particles setting.
14. The owner liked particles hanging and flying with the ramp, so particle motion bends with every beat again. Only the camera stays steady.
15. Repeated actions settle onto strong beats. `BeatTracker.landing` picks the break beat near vanilla's time but pulls it toward beat 1 for bar-long blocks and beats 1 and 3 for two-beat blocks, by at most 30% of the block's time per break, so a chain locks onto the downbeat within a couple of blocks.
16. The HUD got a full pass: four brackets fly in and land on the beat (gold before beat 1), a ring opens where they land, the bar pips pop, the hotbar and bars bounce smoothly in a wave from left to right, and breaking, placing or hitting on the beat answers with sparks in the phrase's disco color.
17. Multiplayer: mining pacing and the instant shield only run in a world this game hosts, mob timing and parries only in one not open to LAN. On servers the mod is visual and sound only.
18. With a diamond pickaxe stone could not be mined: the beat snap broke blocks right at the server's 70% mark, the server queued one early break and dropped the next, and the block came back. Blocks now never break before vanilla would (only the cooldown between blocks is folded in), and `landing` leans early, so a diamond pickaxe on stone keeps one block per beat.
19. Ready cue: the brackets turn red on the beat where a full-strength swing (during a fight) or a full bow draw will be ready, so full hits land in time.
20. Drops: `BeatTracker.drops` marks the bars where the band comes back after a quiet stretch. There a rainbow ring rolls out from the player, fireworks go up around them, the trees let go and the HUD answers.
21. Kills land on the beat: a mob's hurt flash stays on the eighth note, its death waits for the next beat and pops with sparks and a HUD answer.
22. Eating and throwing follow the song. While the player eats or drinks, vanilla's 4-tick chewing timer is off and the mod bites on every eighth note, harder on the beat. Throws, shots and launches play on eighth notes, a throw let go near the beat answers on the HUD, and snowball, egg and splash potion bursts land on the next eighth note with sparks.
23. Mining with a fast pickaxe felt broken, though the log showed every block breaking on the beat. Holding the first block until the next beat made a 0.1 s block take up to 0.35 s, so the player moved on and the break was aborted. The first block of a run now breaks on vanilla time unless a beat is a quarter beat away, and only chained blocks lock to the grid. The server's durability update after each block no longer restarts the next one.
24. Music following borrows from osu! (ppy/osu, ppy/osu-framework): the interpolating audio clock with drift recovery, measured device latency instead of a fixed platform offset, and drawing one frame ahead.
25. Calibration works like lazer's. Tapping skips two warm-up taps and takes the median of the last 16, so a stray tap moves nothing. Hits and used blocks within a quarter beat of a beat are logged, and after 16 of them the menu suggests the offset that centers them, with an Apply button, like lazer's suggested offset from hit errors.
26. Visuals follow the drums the song really plays. `BeatTracker.punch` precomputes, 100 times a second, how fast the bass level climbs (a kick, not the sustained bass line that sits on merengue's off-beats), with the rise and fall of lazer's logo visualiser. The HUD bounce reads it on the ramped visual time, the bracket ring brightens with it, and the camera kick scales by the punch of the beat that opens its cycle.
27. The beat tracker also weighs how fast the bass climbs, so beats sit on the kicks and tracking starts when the band does instead of in a beatless intro. A mining knock or a bite noticed more than two 60 fps frames late, after a hitch, is skipped instead of played off the beat, like lazer's mistimed allowance.

## Code map

- `BeatTracker.java` finds beats (onset envelope, tempo by autocorrelation, dynamic programming after Ellis 2007), shifts them so beat 0 opens a phrase, and measures each beat's loudness. `main` is a self-check with a synthetic click track.
- `MineAtDisco.java` holds everything else: `/disco` (menu, `play`, `stop`, `offset`, `tap`, `x2`, `half`), the `M` and `B` keybinds and `DiscoScreen`, playback on the mod's own OpenAL source, the audio clock, the sound grid, mining, combat, breaking and placing effects, leaves, the HUD.
- `mixin/SoundEngineMixin` holds world sounds until the next grid line and calls `frame()` once per frame.
- `mixin/ClientLevelMixin` takes over the mining hit effect and the block break burst. `mixin/MultiPlayerGameModeMixin` paces block breaking and cracks to the beat and hooks player attacks. `mixin/ItemStackMixin` turns off vanilla chewing so the mod can bite on the beat. `mixin/ClientPacketListenerMixin` and `mixin/LevelEventHandlerMixin` hold thrown item and splash potion bursts for the grid.
- `mixin/LivingEntityMixin` holds mob hurt visuals until the next eighth note and deaths until the next beat, removes the 5-tick shield delay while music plays, and handles perfect parries. `mixin/MeleeAttackGoalMixin` holds melee mob strikes until the beat after a pre-beat warning cue, and `mixin/CreeperMixin` holds creeper detonations until the beat.
- `mixin/LivingEntityRendererMixin` is the mob and player dance and bends walk, hand-swing and death animations during a ramp. `mixin/EntityRendererMixin` bends entity animation during a ramp. `mixin/CameraMixin` is the FOV kick and `mixin/ParticleMixin` bends particle motion during a ramp. `mixin/FallingLeavesInvoker` sheds leaves.
- User-facing text lives in `assets/mineatdisco/lang/en_us.json`. Never hardcode a string the player sees.

## Timing

- `played()` is the mixer position, smoothed by `AudioClock` the way osu-framework's InterpolatingFramedClock does: real time between OpenAL readings, an 80 ms half-life pull toward each fresh reading (which also cancels sound card drift), a jump past 33 ms of error, never backwards. Its self-check runs with `java src/main/java/it/vitoesposito/mineatdisco/AudioClock.java`.
- OpenAL Soft's `AL_SOFT_source_latency` reports the mixer-to-speaker latency, which lazer instead guesses with a fixed platform offset. Visuals follow `visualTime()`: mixer position minus that latency, plus one frame (a frame drawn now shows up a frame later, like lazer's early activation), minus the player's offset. The offset now only covers delay OpenAL can't see, such as Bluetooth, and lives in `disco/audio-offset.txt`.
- Sounds the mod plays share the song's latency, so they follow `played()` directly.
- `beat()` is the visual beat with the ramp applied, and `beatSteady()` the same without it. Animations read `beat()`, `particleSpeed()` or `lagTicks()`, the camera and impact effects read `beatSteady()`, and no visual reads the audio clock. Mining is split: the hit sound plays on the audio eighth note, the chips on the steady visual one, the cracks on the ramped beat.
- `beat()` counts from the first beat of a phrase: `beat % 4` is the position in the bar, `beat % 8` in the phrase. During a velocity ramp it lags behind the real beat and then catches up.
- Grid: footsteps, combat, mining and breaking fall on eighth notes, other world sounds on the next sixteenth. A sound in the first 20% of a grid step plays at once.

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

- Multiplayer support so players in the same world hear and see the same beat grid in sync.
- Connecting Spotify and YouTube Music so the mod can sync to streamed music, not only local `.ogg` files.
- Leaves that sway on the beat. This needs a terrain shader, and Sodium needs its own.
- A clip mode for vertical video and an end-of-song card.
- A license for the repo.

Known limits are marked `ponytail:` in the code: the tracker assumes 4/4 with a fixed phrase phase, tempo folds into 90 to 180 BPM, and every world sound is held for the grid, not only the player's.
