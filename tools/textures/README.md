# Texture generators

Small Java programs that draw the mod's entity textures pixel by pixel, so they can be regenerated
and tweaked without an image editor. Compile and run with the project's JDK, for example:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
cd tools/textures
$JAVA_HOME/bin/javac MakeTravellerV2.java
$JAVA_HOME/bin/java MakeTravellerV2 ../../src/main/resources/assets/thehush/textures/entity
```

- `MakeTravellerV2` writes `traveller.png`, `traveller_dark.png`, `traveller_glow.png`, and
  `traveller_glow_dark.png` for `client/TravellerModel` (128x64; the UV map is in its header comment).
- `MakeEcho` writes `echo.png` (enderman layout, 64x32). Argument: the output file.
- `MakePilgrim` writes `pilgrim.png` and the old-style `traveller_dark.png` from a vanilla villager
  texture (villager layout, 64x64). Arguments: vanilla villager png, output, and the old traveller png.
- `MakeTraveller` is the previous villager-layout Traveller skin, kept for reference.
- `MakeUnsaid` writes `unsaid_0.png`, `unsaid_1.png`, `unsaid_2.png`, and `unsaid_glow.png` on the
  Traveller layout (translucent grey ghosts; the leg regions are left empty). Argument: the output directory.
- `MakeDarkLantern` writes `block/dark_soul_lantern.png` and `item/dark_soul_lantern.png` from the
  vanilla soul lantern textures (first animation frame; glass gone cold, frame a shade darker).
  Arguments: vanilla block soul_lantern.png, vanilla item soul_lantern.png, the `textures` directory.
