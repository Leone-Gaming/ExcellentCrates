package su.nightexpress.excellentcrates.opening.inventory;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import su.nightexpress.excellentcrates.CratesPlugin;
import su.nightexpress.excellentcrates.api.opening.Spinner;
import su.nightexpress.excellentcrates.config.Config;
import su.nightexpress.excellentcrates.crate.impl.CrateSource;
import su.nightexpress.excellentcrates.key.CrateKey;
import su.nightexpress.excellentcrates.opening.AbstractOpening;
import su.nightexpress.excellentcrates.opening.AbstractSpinner;
import su.nightexpress.excellentcrates.opening.inventory.script.ParameterResult;
import su.nightexpress.excellentcrates.opening.inventory.script.ScriptAction;
import su.nightexpress.excellentcrates.opening.inventory.script.ScriptCompiledAction;
import su.nightexpress.excellentcrates.opening.spinner.*;
import su.nightexpress.nightcore.util.random.Rnd;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class InventoryOpening extends AbstractOpening {

    private static final String                    PLACEHOLDER_UNSELECTED_SLOTS = "%unselected_slots%";
    private static final String                    PLACEHOLDER_SELECTED_SLOTS   = "%selected_slots%";
    private static final Function<Integer, String> PLACEHOLDER_SELECTED_SLOT    = slot -> "%selected_slot_" + slot + "%";

    private final InventoryOpeningMenu   menu;
    private final InventoryOpeningConfig config;
    private final Set<Integer>           selectedSlots;
    private final Set<Scheduled>         scheduleds;

    private Inventory inventory;
    private boolean   started;
    private long      closeDelay;
    private boolean popupNextTick;
    //private boolean canRemoveOpening;

    public InventoryOpening(@NotNull CratesPlugin plugin,
                            @NotNull InventoryOpeningMenu menu,
                            @NotNull InventoryOpeningConfig config,
                            @NotNull Player player,
                            @NotNull CrateSource source,
                            @Nullable CrateKey key) {
        super(plugin, player, source, key);
        this.menu = menu;
        this.config = config;
        this.selectedSlots = new HashSet<>();
        this.scheduleds = new HashSet<>();
        this.closeDelay = Config.CRATE_OPENING_CLOSE_TIME.get();
        this.popupNextTick = false;
        //this.canRemoveOpening = false;
    }

    public enum Mode {
        NORMAL, SELECTION
    }

    @Override
    public long getInterval() {
        return 1L;
    }

    public void start() {
        this.runScripts(this.getConfig().getScriptsOnStart());
        this.started = true;
    }

    public void postOpen() {
        this.runScripts(this.getConfig().getScriptsOnOpen());
        this.run();
    }

    @Override
    protected void onLaunch() {

    }

    @Override
    protected void onTick() {
        super.onTick();

        if (this.isPopupNextTick()) {
            this.player.openInventory(this.inventory);
            this.setPopupNextTick(false);
        }

        if (!this.isStarted() && this.isAllSlotsSelected() && this.config.isAutoRun()) {
            this.start();
            return;
        }

        this.scheduleds.removeIf(Scheduled::isCompleted);
        this.scheduleds.forEach(Scheduled::tick);
    }

    @Override
    protected void finalizeStop() {
        this.scheduleds.clear();

        if (this.closeDelay == 0 || this.isEmergency()) {
            this.doClose();
            //AbstractMenu.purge(this.getPlayer());
        }
        else if (this.closeDelay > 0) {
            this.plugin.runTaskLater(task -> this.doClose(), this.closeDelay);
            //AbstractMenu.purge(this.player);
        }
    }

    private void doClose() {
        this.removeOpening();
        this.player.closeInventory();
        this.menu.close(this.getPlayer());
        //this.canRemoveOpening = true;

    }

    /*@Override
    public void removeOpening() {
        if (this.canRemoveOpening) {
            System.out.println("removeOpening");
            super.removeOpening();
        }
    }*/

    @Override
    public boolean isCompleted() {
        if (this.getSpinners().isEmpty()) return false;
        if (!this.scheduleds.isEmpty()) return false;

        return this.getSpinners().stream().allMatch(spinner -> spinner.hasSpin() && (!spinner.isRunning() || spinner.isCompleted()));
    }

    public boolean isAllSlotsSelected() {
        if (this.getMode() != Mode.SELECTION) return false;

        return this.getSelectedSlots().size() >= this.getConfig().getSelectionAmount();
    }

    @Override
    public void instaRoll() {
        //this.startDelay = 0L;
        this.closeDelay = 0L; // Do not schedule inventory closing.
        this.setRefundable(false); // Do not return keys.

        // Select random slots to run for Selection Mode.
        if (this.getMode() == Mode.SELECTION && !this.isAllSlotsSelected()) {
            this.selectedSlots.clear();

            for (int index = 0; index < this.config.getSelectionAmount(); index++) {
                this.selectRewardSlot(this.config.getWinSlots()[index]);
            }
        }

        this.start();
        this.scheduleds.forEach(Scheduled::forceRun);
        this.getSpinners().forEach(spinner -> {
            if (!(spinner instanceof AbstractSpinner abstractSpinner)) return;

            abstractSpinner.setSilent(true);
            //spinner.setCurrentSpins(spinner.getTotalSpins() - 1);
            //spinner.tick();

            long total = Math.max(0L, spinner.getTotalSpins());// * spinner.getInterval();// + spinner.getStartDelay();

            //spinner.setStartDelay(0);
            for (int i = 0; i < total; i++) {
                abstractSpinner.onTick();
            }
            //System.out.println("[spinner ticks] Count: " + spinner.getCurrentSpins() + " / Total: " + spinner.getTotalSpins() + ", Done: " + spinner.isCompleted());
            spinner.stop();
            //System.out.println("still running: " + spinner.isRunning());
        });
        this.getSpinners().removeIf(spinner -> spinner.isRunning() || !spinner.hasSpin());
        this.scheduleds.clear();

        this.stop();
    }

    public void setCloseDelay(long closeDelay) {
        this.closeDelay = closeDelay;
    }

    public boolean isStarted() {
        return started;
    }

    public void selectRewardSlot(int slot) {
        this.selectedSlots.add(slot);
    }

    public void unselectRewardSlot(int slot) {
        this.selectedSlots.remove(slot);
    }

    public boolean isSelectedRewardSlot(int slot) {
        return this.selectedSlots.contains(slot);
    }

    public void schedule(@NotNull Runnable runnable, long delay) {
        Scheduled scheduled = new Scheduled(runnable, delay);
        if (delay <= 0L) {
            scheduled.forceRun();
            return;
        }

        this.scheduleds.add(scheduled);
    }

    public void runReward(@NotNull String id, @NotNull String name, @NotNull SpinMode mode, int[] slots, /*int delay,*/ double chance) {
        RewardSpinSettings settings = this.getConfig().getRewardSpinSettingsMap().get(id.toLowerCase());
        if (settings == null) return;

        this.setHasRewardAttempts(true);

        this.runSpinner(new RewardSpinner(this.plugin, id, settings, this, mode, slots), name, /*delay,*/ chance);
    }

    public void runAnimation(@NotNull String id, @NotNull String name, @NotNull SpinMode mode, int[] slots, /*int delay,*/ double chance) {
        AnimationSpinSettings settings = this.getConfig().getAnimationSpinSettingsMap().get(id.toLowerCase());
        if (settings == null) return;

        this.runSpinner(new AnimationSpinner(this.plugin, id, settings, this, mode, slots), name, /*delay,*/ chance);
    }

    private void runSpinner(@NotNull Spinner spinner, @NotNull String name, /*int delay,*/ double chance) {
        if (!Rnd.chance(chance)) return;

        this.addSpinner(spinner, name/*, delay*/);
    }

    private void runScripts(@NotNull List<String> scripts) {
        scripts.forEach(script -> {
            if (script.isBlank()) return;
            if (this.getMode() == Mode.SELECTION) {
                script = script
                    .replace(PLACEHOLDER_SELECTED_SLOTS, String.join(",", this.getSelectedSlots().stream().map(String::valueOf).toList()))
                    .replace(PLACEHOLDER_UNSELECTED_SLOTS, String.join(",", this.getUnSelectedSlots().stream().map(String::valueOf).toList()));

                int index = 0;
                for (int slot : this.getSelectedSlots()) {
                    script = script.replace(PLACEHOLDER_SELECTED_SLOT.apply(index++), String.valueOf(slot));
                }
            }

            ScriptCompiledAction compiledAction = ScriptCompiledAction.compile(script);
            if (compiledAction == null) return;

            ScriptAction action = compiledAction.getAction();
            ParameterResult result = compiledAction.getParameters();
            action.run(this, result);
        });
    }

    @NotNull
    public Mode getMode() {
        return this.getConfig().getMode();
    }

    @NotNull
    public InventoryOpeningMenu getMenu() {
        return menu;
    }

    @NotNull
    public InventoryOpeningConfig getConfig() {
        return config;
    }

    @NotNull
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(@NotNull Inventory inventory) {
        this.inventory = inventory;
    }

    @NotNull
    public Set<Integer> getSelectedSlots() {
        return selectedSlots;
    }

    @NotNull
    public Set<Integer> getUnSelectedSlots() {
        return IntStream.range(0, this.inventory.getSize()).filter(slot -> !this.isSelectedRewardSlot(slot)).boxed().collect(Collectors.toSet());
    }

    public boolean isPopupNextTick() {
        return popupNextTick;
    }

    public void setPopupNextTick(boolean popupNextTick) {
        this.popupNextTick = popupNextTick;
    }
}
