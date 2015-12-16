package com.doctordark.hcf.listener;

import com.doctordark.hcf.HCF;
import com.doctordark.hcf.faction.struct.Role;
import com.doctordark.hcf.faction.type.Faction;
import com.doctordark.hcf.faction.type.PlayerFaction;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.material.MaterialData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SignSubclaimListener implements Listener {

    private enum SubclaimType {

        LEADER(ImmutableList.of("LEADER", Role.LEADER.getAstrix()),
                ChatColor.DARK_RED.toString() + ChatColor.BOLD + "Leader", Role.LEADER, "Leader"),

        CAPTAIN(ImmutableList.of("CAPTAIN", "OFFICER", Role.CAPTAIN.getAstrix()),
                ChatColor.DARK_RED.toString() + ChatColor.BOLD + "Captain", Role.CAPTAIN, "Captain"),

        MEMBER(ImmutableList.of("PRIVATE", "PERSONAL", "SUBCLAIM", "MEMBER"),
                ChatColor.DARK_RED.toString() + ChatColor.BOLD + "Subclaim", Role.MEMBER, "Member");

        private final List<String> aliases;
        private final String outputText;
        private final String displayName;

        SubclaimType(List<String> aliases, String outputText, Role role, String displayName) {
            this.aliases = aliases;
            this.outputText = outputText;
            this.displayName = displayName;
        }

        //TODO: Configurable
        public boolean isEnabled() {
            switch (this) {
                case LEADER:
                    return false;
                case CAPTAIN:
                    return true;
                case MEMBER:
                    return false;
                default:
                    return false;
            }
        }
    }

    private static final int MAX_SIGN_LINE_CHARS = 16;
    private static final Pattern SQUARE_PATTERN_REPLACER = Pattern.compile("\\[|\\]");
    private static final BlockFace[] SIGN_FACES = new BlockFace[]{
            BlockFace.NORTH,
            BlockFace.EAST,
            BlockFace.SOUTH,
            BlockFace.WEST,
            BlockFace.UP
    };

    private final HCF plugin;

    public SignSubclaimListener(HCF plugin) {
        this.plugin = plugin;
    }

    private SubclaimType getSubclaimType(String value) {
        String typeString = SQUARE_PATTERN_REPLACER.matcher(value.toUpperCase()).replaceAll("");
        for (SubclaimType type : SubclaimType.values()) {
            if (type.aliases.contains(typeString)) {
                return type;
            }
        }

        return null;
    }

    private boolean isSubclaimable(Block block) {
        Material type = block.getType();
        return type == Material.FENCE_GATE || type == Material.TRAP_DOOR || block.getState() instanceof InventoryHolder;
    }

    private SubclaimType getSubclaimType(Sign sign) {
        SubclaimType subclaimType = this.getSubclaimType(sign.getLine(0));
        return subclaimType != null && subclaimType.isEnabled() ? subclaimType : null;
    }

    private SubclaimType getSubclaimType(Block block) {
        if (isSubclaimable(block)) {
            Collection<Sign> attachedSigns = this.getAttachedSigns(block);
            for (Sign attachedSign : attachedSigns) {
                SubclaimType subclaimType = this.getSubclaimType(attachedSign);
                if (subclaimType != null) return subclaimType;
            }
        }

        return null;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSignChange(SignChangeEvent event) {
        String[] lines = event.getLines();
        Block block = event.getBlock();
        MaterialData materialData = block.getState().getData();
        if (materialData instanceof org.bukkit.material.Sign) {
            org.bukkit.material.Sign sign = (org.bukkit.material.Sign) materialData;
            Block attachedBlock = block.getRelative(sign.getAttachedFace());
            if (isSubclaimable(attachedBlock)) {
                Player player = event.getPlayer();
                PlayerFaction playerFaction = plugin.getFactionManager().getPlayerFaction(player);
                if (playerFaction == null) {
                    return; // only allow officers to create Subclaims
                }

                Faction factionAt = plugin.getFactionManager().getFactionAt(block.getLocation());
                if (playerFaction == factionAt) {
                    SubclaimType subclaimType = this.getSubclaimType(attachedBlock);
                    if (subclaimType != null) {
                        player.sendMessage(ChatColor.RED + "There is already a " + subclaimType.displayName + " subclaim sign on this " + attachedBlock.getName() + '.');
                        return;
                    }

                    subclaimType = this.getSubclaimType(lines[0]);
                    if (subclaimType == null || !subclaimType.isEnabled()) {
                        return;
                    }

                    List<String> memberList = null;
                    if (subclaimType == SubclaimType.MEMBER) {
                        memberList = new ArrayList<>(3);
                        for (int i = 1; i < lines.length; i++) {
                            String line = lines[i];
                            if (StringUtils.isNotBlank(line)) {
                                memberList.add(line);
                            }
                        }

                        if (memberList.isEmpty()) {
                            player.sendMessage(ChatColor.RED + "Subclaim signs need to have at least 1 player name inserted.");
                            return;
                        }
                    } else if (subclaimType == SubclaimType.CAPTAIN) {
                        if (playerFaction.getMember(player).getRole() == Role.MEMBER) {
                            player.sendMessage(ChatColor.RED + "Only faction officers can create captain subclaimed objects.");
                            return;
                        }

                        // Clear the other lines.
                        event.setLine(1, null);
                        event.setLine(2, null);
                        event.setLine(3, null);
                    } else if (subclaimType == SubclaimType.LEADER) {
                        if (playerFaction.getMember(player).getRole() != Role.LEADER) {
                            player.sendMessage(ChatColor.RED + "Only faction leaders can create leader subclaimed objects.");
                            return;
                        }

                        // Clear the other lines.
                        event.setLine(1, null);
                        event.setLine(2, null);
                        event.setLine(3, null);
                    }

                    // Finalise the subclaim.
                    event.setLine(0, subclaimType.outputText);
                    StringBuilder builder = new StringBuilder(plugin.getConfiguration().getRelationColourTeammate() + player.getName() +
                            ChatColor.YELLOW + " has created a subclaim on block type " + ChatColor.AQUA + attachedBlock.getName() +
                            ChatColor.YELLOW + " at " + ChatColor.WHITE + '(' + attachedBlock.getX() + ", " + attachedBlock.getZ() + ')' + ChatColor.YELLOW + " for ");

                    if (subclaimType == SubclaimType.LEADER) {
                        builder.append("leaders");
                    } else if (subclaimType == SubclaimType.CAPTAIN) {
                        builder.append("captains");
                    } else if (memberList != null) { // Should never be null, but best safe; SubclaimType.PRIVATE
                        builder.append("members ").append(ChatColor.RED).append('[');
                        builder.append(Joiner.on(", ").join(memberList.stream().filter(string -> playerFaction.getMember(string) != null).collect(Collectors.toList()))).append("]");
                    }

                    playerFaction.broadcast(builder.toString());
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getEotwHandler().isEndOfTheWorld()) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE && player.hasPermission(ProtectionListener.PROTECTION_BYPASS_PERMISSION)) {
            return;
        }

        Block block = event.getBlock();
        BlockState state = block.getState();

        Block subclaimObjectBlock = null;
        if (!(state instanceof Sign)) {
            subclaimObjectBlock = block;
        } else {
            Sign sign = (Sign) state;
            MaterialData signData = sign.getData();
            if (signData instanceof org.bukkit.material.Sign) {
                org.bukkit.material.Sign materialSign = (org.bukkit.material.Sign) signData;
                subclaimObjectBlock = block.getRelative(materialSign.getAttachedFace());
            }
        }

        if (subclaimObjectBlock != null && !this.checkSubclaimIntegrity(player, subclaimObjectBlock)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot break this subclaimed " + subclaimObjectBlock.getName() + '.');
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (plugin.getEotwHandler().isEndOfTheWorld()) {
            return;
        }

        // Have to do this hackery since Bukkit doesn't
        // provide an API for us to do this
        InventoryHolder holder = event.getSource().getHolder();
        Collection<Block> sourceBlocks;
        if (holder instanceof Chest) {
            sourceBlocks = Collections.singletonList(((Chest) holder).getBlock());
        } else if (holder instanceof DoubleChest) {
            DoubleChest doubleChest = (DoubleChest) holder;
            sourceBlocks = Lists.newArrayList(((Chest) doubleChest.getLeftSide()).getBlock(), ((Chest) doubleChest.getRightSide()).getBlock());
        } else {
            return;
        }

        for (Block block : sourceBlocks) {
            if (this.getSubclaimType(block) != null) {
                event.setCancelled(true);
                break;
            }
        }
    }

    private String getShortenedName(String originalName) {
        if (originalName.length() >= MAX_SIGN_LINE_CHARS) {
            originalName = originalName.substring(0, MAX_SIGN_LINE_CHARS);
        }

        return originalName;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Player player = event.getPlayer();
            if (player.getGameMode() == GameMode.CREATIVE && player.hasPermission(ProtectionListener.PROTECTION_BYPASS_PERMISSION)) {
                return;
            }

            if (plugin.getEotwHandler().isEndOfTheWorld() || plugin.getConfiguration().isKitMap()) {
                return;
            }

            Block block = event.getClickedBlock();
            if (!checkSubclaimIntegrity(player, block)) {
                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "You do not have access to this subclaimed " + block.getName() + '.');
            }
        }
    }

    /**
     * Checks subclaim integrity of a {@link Block} for a {@link Player}.
     *
     * @param player         the {@link Player} to check
     * @param subclaimObject the {@link Block} to check
     * @return true if allowed to open
     */
    private boolean checkSubclaimIntegrity(Player player, Block subclaimObject) {
        if (!isSubclaimable(subclaimObject)) {
            return true; // Not even subclaimed.
        }

        PlayerFaction playerFaction = plugin.getFactionManager().getPlayerFaction(player);
        if (playerFaction == null || playerFaction.isRaidable()) {
            return true; // If the faction is raidable, just allow it.
        }

        Role role = playerFaction.getMember(player).getRole();
        if (role == Role.LEADER) {
            return true; // Let leaders open regardless.
        }

        if (playerFaction != plugin.getFactionManager().getFactionAt(subclaimObject)) {
            return true; // Let enemies be able to open
        }

        Collection<Sign> attachedSigns = getAttachedSigns(subclaimObject);
        if (attachedSigns.isEmpty()) {
            return true;
        }

        String search = null; // lazy-loaded
        for (Sign attachedSign : attachedSigns) {
            SubclaimType subclaimType = getSubclaimType(attachedSign);
            if (subclaimType == null) {
                continue;
            }

            // No need to conditional check leaders as they can open anything.
            if (subclaimType == SubclaimType.CAPTAIN) {
                if (role == Role.MEMBER) {
                    continue;
                }

                return true;
            } else if (subclaimType == SubclaimType.MEMBER) {
                if (search == null) search = this.getShortenedName(player.getName());

                String[] lines = attachedSign.getLines();
                for (int i = 1; i < lines.length; i++) {
                    if (lines[i].contains(search)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Gets the attached {@link Sign}s on a {@link Block}.
     *
     * @param block the {@link Block} to get for
     * @return collection of attached {@link Sign}s
     */
    public Collection<Sign> getAttachedSigns(Block block) {
        Set<Sign> results = new HashSet<>();
        getSignsAround(block, results);

        BlockState state = block.getState();
        if (state instanceof Chest) {
            Inventory chestInventory = ((Chest) state).getInventory();
            if (chestInventory instanceof DoubleChestInventory) {
                DoubleChest doubleChest = ((DoubleChestInventory) chestInventory).getHolder();
                Block left = ((Chest) doubleChest.getLeftSide()).getBlock();
                Block right = ((Chest) doubleChest.getRightSide()).getBlock();
                getSignsAround(left.equals(block) ? right : left, results);
            }
        }

        return results;
    }

    /**
     * Gets the {@link Sign}s that are attached to a given {@link Block}.
     *
     * @param block   the {@link Block} to get around
     * @param results the input to add to
     * @return the updated set of {@link Sign}s
     */
    private Set<Sign> getSignsAround(Block block, Set<Sign> results) {
        for (BlockFace face : SIGN_FACES) {
            Block relative = block.getRelative(face);
            BlockState relativeState = relative.getState();
            if (relativeState instanceof Sign) {
                org.bukkit.material.Sign materialSign = (org.bukkit.material.Sign) relativeState.getData();
                if (relative.getRelative(materialSign.getAttachedFace()).equals(block)) {
                    results.add((Sign) relative.getState());
                }
            }
        }

        return results;
    }
}
