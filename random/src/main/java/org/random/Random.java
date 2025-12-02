package org.random;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class Random extends JavaPlugin implements Listener, TabExecutor {

    // 플레이어별 뽑기 세션 관리
    private final Map<UUID, LuckySession> sessions = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("luckybox").setExecutor(this);
        getLogger().info("LuckyBox enabled!");
    }

    @Override
    public void onDisable() {
        // 플러그인 꺼질 때 모든 애니메이션 태스크 종료
        for (LuckySession session : sessions.values()) {
            session.stop();
        }
        sessions.clear();
    }

    // /luckybox 명령으로 테스트용 상자 열기
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("플레이어만 사용할 수 있습니다.");
            return true;
        }

        openLuckyBox(player);
        return true;
    }

    // 탭완성 안 쓸거라 비워둬도 됨
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }

    /**
     * 럭키박스 GUI 열기
     */
    public void openLuckyBox(Player player) {
        // 3줄짜리 상자 (27칸)
        Inventory inv = Bukkit.createInventory(player, 27, ChatColor.DARK_PURPLE + "럭키 박스");

        // 일단 바깥쪽은 유리판 효과용으로 채워두고, 가운데(13)는 비워두기
        fillGlass(inv);

        // 뽑기 후보 아이템
        List<ItemStack> candidates = createExampleCandidates();

        LuckySession session = new LuckySession(player.getUniqueId(), inv, candidates);
        sessions.put(player.getUniqueId(), session);
        session.startAnimation();

        player.openInventory(inv);
    }

    /**
     * 바깥쪽 슬롯 유리판 채우기 (애니메이션용)
     */
    private void fillGlass(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            if (i == 13) continue; // 정중앙은 비워두기
            inv.setItem(i, createGlassPane(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        }
    }

    private ItemStack createGlassPane(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * 나중에 실제 아이템 + 확률 구조로 바꾸면 됨
     */
    private List<ItemStack> createExampleCandidates() {
        List<ItemStack> list = new ArrayList<>();

        list.add(namedItem(Material.DIAMOND_SWORD, ChatColor.AQUA + "다이아 검"));
        list.add(namedItem(Material.NETHERITE_INGOT, ChatColor.DARK_GRAY + "네더라이트 주괴"));
        list.add(namedItem(Material.GOLDEN_APPLE, ChatColor.GOLD + "황금 사과"));
        list.add(namedItem(Material.EMERALD, ChatColor.GREEN + "에메랄드"));
        list.add(namedItem(Material.IRON_INGOT, ChatColor.WHITE + "철 주괴"));

        return list;
    }

    private ItemStack namedItem(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * GUI 클릭 이벤트 처리
     */
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        LuckySession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        if (!event.getView().getTitle().equals(ChatColor.DARK_PURPLE + "럭키 박스")) return;
        if (!event.getInventory().equals(session.inventory)) return;

        // 상자 안에서 일단 전부 클릭 막기
        event.setCancelled(true);

        // 아직 결과 안 나왔으면 아무 것도 못하게
        if (!session.finished) return;

        // 결과 나왔고, 중앙 슬롯을 클릭했을 때 보상 지급
        if (event.getRawSlot() == 13) {
            session.giveReward(player);
            player.closeInventory();
            sessions.remove(player.getUniqueId());
        }
    }

    /**
     * GUI 강제로 닫았을 때 태스크 정리
     */
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        LuckySession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        if (event.getView().getTitle().equals(ChatColor.DARK_PURPLE + "럭키 박스")) {
            session.stop();
            sessions.remove(player.getUniqueId());
        }
    }

    /**
     * 한 명의 플레이어에 대한 뽑기 세션
     */
    private class LuckySession {
        private final UUID playerId;
        private final Inventory inventory;
        private final List<ItemStack> candidates;

        private BukkitRunnable animationTask;
        private BukkitRunnable endTask;

        private boolean finished = false;
        private ItemStack finalReward;

        // 유리판 색 회전용 색 목록
        private final Material[] glassColors = {
                Material.RED_STAINED_GLASS_PANE,
                Material.ORANGE_STAINED_GLASS_PANE,
                Material.YELLOW_STAINED_GLASS_PANE,
                Material.GREEN_STAINED_GLASS_PANE,
                Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                Material.BLUE_STAINED_GLASS_PANE,
                Material.PURPLE_STAINED_GLASS_PANE,
                Material.MAGENTA_STAINED_GLASS_PANE
        };
        private int glassIndex = 0;
        private int candidateIndex = 0;

        LuckySession(UUID playerId, Inventory inv, List<ItemStack> candidates) {
            this.playerId = playerId;
            this.inventory = inv;
            this.candidates = candidates;
        }

        void startAnimation() {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;

            // 3틱마다 애니메이션 (약 0.15초)
            animationTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (finished) {
                        cancel();
                        return;
                    }

                    // 유리판 색 바꾸기
                    glassIndex = (glassIndex + 1) % glassColors.length;
                    Material glassMat = glassColors[glassIndex];

                    for (int i = 0; i < inventory.getSize(); i++) {
                        if (i == 13) continue;
                        inventory.setItem(i, createGlassPane(glassMat, " "));
                    }

                    // 중앙 슬롯에 현재 후보 아이템 보여주기
                    candidateIndex = (candidateIndex + 1) % candidates.size();
                    ItemStack display = candidates.get(candidateIndex).clone();

                    ItemMeta meta = display.getItemMeta();
                    if (meta != null) {
                        List<String> lore = new ArrayList<>();
                        lore.add(ChatColor.GRAY + "돌아가는 중...");
                        meta.setLore(lore);
                        display.setItemMeta(meta);
                    }
                    inventory.setItem(13, display);

                    // 소리 효과 (옵션)
                    player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
                }
            };
            // 🔥 여기
            animationTask.runTaskTimer(Random.this, 0L, 3L);

            // 10초 후 결과 확정
            endTask = new BukkitRunnable() {
                @Override
                public void run() {
                    if (finished) return;
                    finished = true;

                    // 여기서 나중에 "확률" 계산해서 당첨 아이템 뽑으면 됨
                    // 지금은 그냥 현재 candidateIndex 기준으로 선택
                    finalReward = candidates.get(candidateIndex).clone();

                    // 중앙 슬롯에 "클릭해서 받으세요" 같은 설명 붙이기
                    ItemMeta meta = finalReward.getItemMeta();
                    if (meta != null) {
                        List<String> lore = new ArrayList<>();
                        lore.add(ChatColor.GOLD + "당첨!");
                        lore.add(ChatColor.YELLOW + "클릭해서 아이템을 받으세요.");
                        meta.setLore(lore);
                        finalReward.setItemMeta(meta);
                    }
                    inventory.setItem(13, finalReward);

                    Player p = Bukkit.getPlayer(playerId);
                    if (p != null) {
                        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                    }
                }
            };
            // 🔥 그리고 여기
            endTask.runTaskLater(Random.this, 200L);
        }


        void stop() {
            if (animationTask != null) {
                animationTask.cancel();
            }
            if (endTask != null) {
                endTask.cancel();
            }
        }

        void giveReward(Player player) {
            if (finalReward == null) return;

            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(finalReward);
            if (!overflow.isEmpty()) {
                // 인벤 꽉 찼으면 발밑에 떨어뜨리기
                overflow.values().forEach(item ->
                        player.getWorld().dropItemNaturally(player.getLocation(), item)
                );
            }
            player.sendMessage(ChatColor.GOLD + "럭키 박스에서 "
                    + ChatColor.RESET + finalReward.getItemMeta().getDisplayName()
                    + ChatColor.GOLD + " 를(을) 획득했습니다!");
        }
    }
}
