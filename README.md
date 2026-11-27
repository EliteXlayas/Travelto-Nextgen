# Travelto-Nextgen 0.3.15

Travelto Nextgen is a Minecraft 26.3 Fabric Visual&Assist Mod, which brings many fun and useful features to the game.

## Codes we use: ModernUI , Forge Config API port,SeedCrackerX

- BioCamLimb is the original author of ModernUI and Arc3D https://github.com/BloCamLimb/ModernUI . 
- DragonHua (https://github.com/DragonHua) made the original Fabric Port.
- ModernUI (LGPL-3.0-or-later) and [Forge Config API Port](https://github.com/Fuzss/forge-config-api-port/tree/v26.3.1-mc26.3.x) (MPL-2.0) are bundled byte-for-byte, not shaded or merged. License notices and source links are in the JAR’s `META-INF/licenses/`.
- Fuzss is the author of Forge Config API port(https://github.com/Fuzss/forge-config-api-port)
- SeedcrackerX by 19MisterX98(https://github.com/19MisterX98/SeedcrackerX)

## Main Features:

### For Travelto itself(Client)
- Custom Bloom effect, colors, Background Blur, Animation, Text Glow effect, HUD settings...
- **Languages**: For now , we only have Chinese and English. 
- **Alert**: When the server changes your position, rotation, items(inventory) frequently, Alert system will identify them and remind you suspicious check or server lag.
### Renders
- **WaterMark**: Has 2 styles: Logo and Dynamic Island. When switched to Dynamic island Mode, Module's status,Notices, Blocks can be displayed on it. Every features can be highly customized.
- **TargetHUD**: has 2 styles: Travelto and Skyline. When switched to Travelto, Mob icons are displayed. For mob icons, you can choose what it should display. One is Portrait, the other is SpawnEgg. Additionally, you can choose TargetHUD's styles. For instances, Follow target, Split/Merge and custom aim distance. Will Target at most 4 targets.
- **ArrayList**: Simply display the modules you are using. The text color can be modified.
- **Ping**: The default bind is Mouse5. Only can you see your Markers.(*light 'em up!*)
- **MenuShader**: Can be turned on or off.
- **HUD Editor**: Scale, color and visibility are variable.
- **FreeCam**: Support custom fly speed, auto-cancel.
- **DamageParticles** : Render some damage numbers and custom particles while you are dealing damage.
- **ESP**: See through specific entities while turning on
- **Tracers**: Line up specific entities to your crosshair.
- **Effects**: Display Effects you current have. (Replaces Vanilla effects display)
- **X-Ray**: Ore persets mode and custom mode. Seed recovery available on singleplayer.
### Movement
- **Fly**: May not work properly.

### World
- **Nuker**: Automatically mining/farming/foraging custom blocks in range. Target mode and turn style are ez to modify. Block groups are supported. 

### Targets
- Allow you to change what kind of creatures you want to attack or render by ESP.

---

## How to Install Travelto-Nextgen?

### These are the things you need:
- Minecraft 26.3 Fabric
- Fabric langauge kotlin(1.14.2+kotlin.2.4.21)
