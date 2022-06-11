package me.pieking1215.invmove;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import me.pieking1215.invmove.module.Module;
import me.pieking1215.invmove.module.VanillaModule16;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.Input;
import net.minecraft.network.chat.MutableComponent;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

public abstract class InvMove {

    public static InvMove instance;

    public static final String MOD_ID = "invmove";

    private static final KeyMapping TOGGLE_MOVEMENT_KEY = new KeyMapping(
            "keybind.invmove.toggleMove",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            "keycategory.invmove"
    );

    private static List<Module> addonModules = new ArrayList<>();

    /**
     * Use this function to register addon `Module`s.
     * This should be done during `InterModEnqueueEvent` on forge or the "invmove" entrypoint on fabric/quilt
     */
    public static void registerModule(Module module) {
        (instance != null ? instance.modules : addonModules).add(module);
    }

    // crossversion compatibility layer

    public abstract Optional<String> modidFromClass(Class<?> c);
    public abstract String modNameFromModid(String modid);
    public abstract File configDir();
    protected abstract void registerKeybind(KeyMapping key);

    public abstract MutableComponent translatableComponent(String key);
    public abstract MutableComponent literalComponent(String text);

    public abstract boolean optionToggleCrouch();
    public abstract void setOptionToggleCrouch(boolean toggleCrouch);

    // implementation

    protected boolean wasSneaking = false;
    protected boolean wasShiftDown = false;
    protected boolean wasToggleMovementPressed = false;

    public List<Module> modules = new ArrayList<>();

    public InvMove() {
        this.modules.addAll(addonModules);
        addonModules.clear();
        this.modules.add(0, this.getVanillaModule());

        this.registerKeybind(TOGGLE_MOVEMENT_KEY);
    }

    public Module getVanillaModule() {
        return new VanillaModule16();
    }

    public void finishInit(){
        InvMoveConfig.load();
    }

    /**
     * Handles updating and applying effects of the toggle movement key.
     * This is more complicated than you might expect because we want to toggle the config value
     *   only if it actually has an effect.
     * So for example, pressing it while in a text field won't do anything
     * @param screen The current screen
     * @param couldMove Whether we could move in this Screen before
     * @return Whether we can move in this Screen now
     */
    private boolean handleToggleMovementKey(Screen screen, boolean couldMove) {
        if (TOGGLE_MOVEMENT_KEY.isUnbound()) return couldMove;

        // .key here is accessWidened
        TOGGLE_MOVEMENT_KEY.setDown(InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), TOGGLE_MOVEMENT_KEY.key.getValue()));
        boolean before = wasToggleMovementPressed;
        wasToggleMovementPressed = TOGGLE_MOVEMENT_KEY.isDown;

        // if button pressed
        if (TOGGLE_MOVEMENT_KEY.isDown && !before) {

            if (screen == null) {
                InvMoveConfig.MOVEMENT.ENABLED.set(!InvMoveConfig.MOVEMENT.ENABLED.get());
                return couldMove;
            }

            // if we could move before
            if (couldMove && InvMoveConfig.MOVEMENT.ENABLED.get()) {
                // toggle movement off
                InvMoveConfig.MOVEMENT.ENABLED.set(false);
                return false;
            }

            // if we couldn't move before
            if (!couldMove && !InvMoveConfig.MOVEMENT.ENABLED.get()) {
                // try turning movement on and see if that makes us able to move
                InvMoveConfig.MOVEMENT.ENABLED.set(true);
                if (allowMovementInScreen(screen)) {
                    // if we are allowed to move now, keep the change
                    return true;
                } else{
                    // if we are still not allowed to move, revert the change
                    InvMoveConfig.MOVEMENT.ENABLED.set(false);
                    return false;
                }
            }
        }

        return couldMove;
    }

    public void onInputUpdate(Input input){
        if(Minecraft.getInstance().player == null) return;

        if(Minecraft.getInstance().screen == null) {
            wasSneaking = input.shiftKeyDown;
        }

        boolean canMove = allowMovementInScreen(Minecraft.getInstance().screen);
        canMove = handleToggleMovementKey(Minecraft.getInstance().screen, canMove);

        if(canMove){

            // tick keybinds (since opening the ui unpresses all keys)

            if (optionToggleCrouch()) {
                // TODO: think about doing this a better way

                // save whether it was toggled
                boolean wasCrouchToggle = Minecraft.getInstance().options.keyShift.isDown;

                // make it not toggle, and see if the key was pressed
                setOptionToggleCrouch(false);
                KeyMapping.setAll();
                setOptionToggleCrouch(true);

                // manually toggle crouch
                boolean nowShift = Minecraft.getInstance().options.keyShift.isDown;
                if (InvMoveConfig.MOVEMENT.SNEAK.get() == InvMoveConfig.Movement.SneakMode.Pressed && !wasShiftDown && nowShift) {
                    Minecraft.getInstance().options.keyShift.isDown = !wasCrouchToggle;
                } else {
                    Minecraft.getInstance().options.keyShift.isDown = wasCrouchToggle;
                }
                wasShiftDown = nowShift;
            } else {
                KeyMapping.setAll();
            }
//            Minecraft.getInstance().screen.passEvents = true;
//            System.out.println(input.up + " " + Minecraft.getInstance().options.keyUp.isDown);

            // this is needed for compatibility with ItemPhysic
            Minecraft.getInstance().options.keyDrop.setDown(false);

            if (!optionToggleCrouch()) {
                if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.isPassenger()) {
                    Minecraft.getInstance().options.keyShift.setDown(InvMoveConfig.MOVEMENT.DISMOUNT.get() && Minecraft.getInstance().options.keyShift.isDown);
                } else {
                    boolean sneakKey = false;
                    switch (InvMoveConfig.MOVEMENT.SNEAK.get()) {
                        case Off:
                            break;
                        case Maintain:
                            sneakKey = wasSneaking;
                            break;
                        case Pressed:
                            // update wasSneaking so we know what to do when allowMovementInScreen -> false
                            sneakKey = wasSneaking = Minecraft.getInstance().options.keyShift.isDown;
                            break;
                    }

                    Minecraft.getInstance().options.keyShift.setDown(sneakKey);
                }
            }

            // tick movement
            manualTickMovement(input, Minecraft.getInstance().player.isMovingSlowly(), Minecraft.getInstance().player.isSpectator());

            // set sprinting using raw keybind data
            // edit: this is commented out to let vanilla handle it (requires an extra mixin on forge)
            //       letting vanilla do it might fix some bugs with other mods that affect sprinting
//            if(!Minecraft.getInstance().player.isSprinting() && !Minecraft.getInstance().player.isCrouching()) {
//                Minecraft.getInstance().player.setSprinting(rawIsKeyDown(Minecraft.getInstance().options.keySprint));
//            }

        }else if(Minecraft.getInstance().screen != null){
            // we are in a screen that we can't move in

            KeyMapping.releaseAll();

            // special handling for sneaking
            if (InvMoveConfig.GENERAL.ENABLED.get() && !optionToggleCrouch()) {
                if (Minecraft.getInstance().player == null || !Minecraft.getInstance().player.isPassenger()) {
                    boolean sneakKey = false;
                    switch (InvMoveConfig.MOVEMENT.SNEAK.get()) {
                        case Off:
                            break;
                        case Maintain:
                        case Pressed:
                            sneakKey = wasSneaking;
                            break;
                    }

                    Minecraft.getInstance().options.keyShift.setDown(sneakKey);
                    input.shiftKeyDown = sneakKey;
                }
            }
        }
    }

    /**
     * Returns `true` if the local player is allowed to move in this `Screen`, `false` otherwise.
     */
    public boolean allowMovementInScreen(Screen screen) {
        if(screen == null) return false;

        if(Minecraft.getInstance().player == null) return false;

        if(!InvMoveConfig.GENERAL.ENABLED.get()) return false;
        if(!InvMoveConfig.MOVEMENT.ENABLED.get()) return false;

        if(screen.isPauseScreen() && Minecraft.getInstance().hasSingleplayerServer()){
            if(Minecraft.getInstance().getSingleplayerServer() != null) {
                if (!Minecraft.getInstance().getSingleplayerServer().isPublished()) return false;
            } else {
                return false;
            }
        }

        Optional<Boolean> movement = Optional.empty();
        modules: for (Module mod : this.modules) {
            Module.Movement res = mod.shouldAllowMovement(screen);
            switch (res) {
                case PASS:
                    break;
                case FORCE_ENABLE:
                    movement = Optional.of(true);
                    break modules;
                case FORCE_DISABLE:
                    movement = Optional.of(false);
                    break modules;
                case SUGGEST_ENABLE:
                    movement = Optional.of(true);
                    break;
                case SUGGEST_DISABLE:
                    movement = Optional.of(false);
                    break;
            }
        }

        if (!movement.isPresent()) {
            Class<? extends Screen> cl = screen.getClass();
            String modid = modidFromClass(cl).orElse("?unknown");
            InvMoveConfig.MOVEMENT.unrecognizedScreensAllowMovement.putIfAbsent(modid, new HashMap<>());
            HashMap<Class<? extends Screen>, Boolean> hm = InvMoveConfig.MOVEMENT.unrecognizedScreensAllowMovement.get(modid);

            if (!hm.containsKey(cl)) {
                hm.put(cl, InvMoveConfig.MOVEMENT.UNRECOGNIZED_SCREEN_DEFAULT.get());
                InvMoveConfig.save();
            }

            return hm.get(cl);
        } else {
            return movement.get();
        }
    }

    public static Field[] getDeclaredFieldsSuper(Class<?> aClass) {
        List<Field> fs = new ArrayList<>();

        do{
            fs.addAll(Arrays.asList(aClass.getDeclaredFields()));
        }while((aClass = aClass.getSuperclass()) != null);

        return fs.toArray(new Field[0]);
    }

    /**
     * Clone of Input.tick but uses raw keybind data
     */
    public void manualTickMovement(Input input, boolean slow, boolean noDampening) {

        input.up = rawIsKeyDown(Minecraft.getInstance().options.keyUp);
        input.down = rawIsKeyDown(Minecraft.getInstance().options.keyDown);
        input.left = rawIsKeyDown(Minecraft.getInstance().options.keyLeft);
        input.right = rawIsKeyDown(Minecraft.getInstance().options.keyRight);
        input.forwardImpulse = input.up == input.down ? 0.0F : (float)(input.up ? 1 : -1);
        input.leftImpulse = input.left == input.right ? 0.0F : (float)(input.left ? 1 : -1);
        input.jumping = rawIsKeyDown(Minecraft.getInstance().options.keyJump) && InvMoveConfig.MOVEMENT.JUMP.get();

        input.shiftKeyDown = rawIsKeyDown(Minecraft.getInstance().options.keyShift);
        if (!noDampening && (input.shiftKeyDown || slow)) {
            input.leftImpulse = (float)((double)input.leftImpulse * 0.3D);
            input.forwardImpulse = (float)((double)input.forwardImpulse * 0.3D);
        }
    }

    public static boolean rawIsKeyDown(KeyMapping key){
        // this field is accesswidened
        // can't use the method because it has extra conditions in forge
        return key.isDown;
    }

    /**
     * Returns `true` if this `Screen` should have its background tint hidden, `false` otherwise.
     */
    public boolean shouldDisableScreenBackground(Screen screen) {

        if(Minecraft.getInstance().player == null) return false;

        if(!InvMoveConfig.GENERAL.ENABLED.get()) return false;

        if(!InvMoveConfig.BACKGROUND.BACKGROUND_HIDE.get()) return false;

        if(screen == null) return false;

        if(screen.isPauseScreen()){
            switch (InvMoveConfig.BACKGROUND.HIDE_ON_PAUSE.get()) {
                case Show:
                    return false;
                case AllowHide:
                    break;
                case ShowSP:
                    if (Minecraft.getInstance().hasSingleplayerServer()) {
                        if(Minecraft.getInstance().getSingleplayerServer() != null) {
                            if (!Minecraft.getInstance().getSingleplayerServer().isPublished()) return false;
                        } else {
                            return false;
                        }
                    }
                    break;
            }
        }

        Optional<Boolean> show = Optional.empty();
        modules: for (Module mod : this.modules) {
            Module.Background res = mod.shouldHideBackground(screen);
            switch (res) {
                case PASS:
                    break;
                case FORCE_SHOW:
                    show = Optional.of(true);
                    break modules;
                case FORCE_HIDE:
                    show = Optional.of(false);
                    break modules;
                case SUGGEST_SHOW:
                    show = Optional.of(true);
                    break;
                case SUGGEST_HIDE:
                    show = Optional.of(false);
                    break;
            }
        }

        if (!show.isPresent()) {
            Class<? extends Screen> cl = screen.getClass();
            String modid = modidFromClass(cl).orElse("?unknown");
            InvMoveConfig.BACKGROUND.unrecognizedScreensHideBG.putIfAbsent(modid, new HashMap<>());
            HashMap<Class<? extends Screen>, Boolean> hm = InvMoveConfig.BACKGROUND.unrecognizedScreensHideBG.get(modid);

            if (!hm.containsKey(cl)) {
                hm.put(cl, InvMoveConfig.BACKGROUND.UNRECOGNIZED_SCREEN_DEFAULT.get());
                InvMoveConfig.save();
            }

            return hm.get(cl);
        } else {
            return !show.get();
        }
    }

    /**
     * Draws the class name of the current `Screen` and its superclasses, along with their
     *   modid and their movement and background state.
     */
    public void drawDebugOverlay() {
        if(InvMoveConfig.GENERAL.DEBUG_DISPLAY.get()) {
            Screen screen = Minecraft.getInstance().screen;
            if(screen == null) return;

            int i = 0;
            Class<?> cl = screen.getClass();
            while (cl.getSuperclass() != null) {
                String className = cl.getName();
                if (className.startsWith("net.minecraft.")) {
                    className = className.substring("net.minecraft.".length());
                }

                Optional<String> modid = this.modidFromClass(cl);
                if (modid.isPresent()) {
                    className = "[" + modid.get() + "] " + className;
                }
                if (shouldDisableScreenBackground(screen)) {
                    className = "B" + className;
                }
                if (allowMovementInScreen(screen)) {
                    className = "M" + className;
                }
                Minecraft.getInstance().font.drawShadow(new PoseStack(), className, 4, 4 + 10 * i, 0xffffffff);

                i++;
                cl = cl.getSuperclass();
            }
        }
    }

}
