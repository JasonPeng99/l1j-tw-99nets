/**
 *                            License
 * THE WORK (AS DEFINED BELOW) IS PROVIDED UNDER THE TERMS OF THIS  
 * CREATIVE COMMONS PUBLIC LICENSE ("CCPL" OR "LICENSE"). 
 * THE WORK IS PROTECTED BY COPYRIGHT AND/OR OTHER APPLICABLE LAW.  
 * ANY USE OF THE WORK OTHER THAN AS AUTHORIZED UNDER THIS LICENSE OR  
 * COPYRIGHT LAW IS PROHIBITED.
 * 
 * BY EXERCISING ANY RIGHTS TO THE WORK PROVIDED HERE, YOU ACCEPT AND  
 * AGREE TO BE BOUND BY THE TERMS OF THIS LICENSE. TO THE EXTENT THIS LICENSE  
 * MAY BE CONSIDERED TO BE A CONTRACT, THE LICENSOR GRANTS YOU THE RIGHTS CONTAINED 
 * HERE IN CONSIDERATION OF YOUR ACCEPTANCE OF SUCH TERMS AND CONDITIONS.
 * 
 */
package l1j.server.server.clientpackets;

import l1j.server.server.ClientThread;
import l1j.server.server.datatables.PetTypeTable;
import l1j.server.server.model.L1Inventory;
import l1j.server.server.model.L1Object;
import l1j.server.server.model.L1PcInventory;
import l1j.server.server.model.L1World;
import l1j.server.server.model.Instance.L1ItemInstance;
import l1j.server.server.model.Instance.L1NpcInstance;
import l1j.server.server.model.Instance.L1PcInstance;
import l1j.server.server.model.Instance.L1PetInstance;
import l1j.server.server.model.Instance.L1SummonInstance;
import l1j.server.server.serverpackets.S_ItemName;
import l1j.server.server.serverpackets.S_ServerMessage;
import l1j.server.server.templates.L1Npc;
import l1j.server.server.templates.L1PetType;
import l1j.server.server.utils.Random;

/**
 * 處理收到由客戶端傳來給道具的封包
 */
public class C_GiveItem extends ClientBasePacket {
	private static final String C_GIVE_ITEM = "[C] C_GiveItem";

	public C_GiveItem(byte decrypt[], ClientThread client) {
		super(decrypt);
		int targetId = readD();
		readH();
		readH();
		int itemId = readD();
		int count = readD();

		L1PcInstance pc = client.getActiveChar();
		if (pc.isGhost()) {
			return;
		}

		L1Object object = L1World.getInstance().findObject(targetId);
		if ((object == null) || !(object instanceof L1NpcInstance)) {
			return;
		}
		L1NpcInstance target = (L1NpcInstance) object;
		if (!isNpcItemReceivable(target.getNpcTemplate())) {
			return;
		}
		L1Inventory targetInv = target.getInventory();

		L1Inventory inv = pc.getInventory();
		L1ItemInstance item = inv.getItem(itemId);
		if (item == null) {
			return;
		}
		if (item.isEquipped()) {
			pc.sendPackets(new S_ServerMessage(141)); // \f1你不能夠將轉移已經裝備的物品。
			return;
		}
		if (!item.getItem().isTradable()) {
			pc.sendPackets(new S_ServerMessage(210, item.getItem().getName())); // \f1%0%d是不可轉移的…
			return;
		}
		if (item.getBless() >= 128) { // 封印的裝備
			// \f1%0%d是不可轉移的…
			pc.sendPackets(new S_ServerMessage(210, item.getItem().getName()));
			return;
		}
		for (Object petObject : pc.getPetList().values()) {
			if (petObject instanceof L1PetInstance) {
				L1PetInstance pet = (L1PetInstance) petObject;
				if (item.getId() == pet.getItemObjId()) {
					// \f1%0%d是不可轉移的…
					pc.sendPackets(new S_ServerMessage(210, item.getItem().getName()));
					return;
				}
			}
		}
		if (targetInv.checkAddItem(item, count) != L1Inventory.OK) {
			pc.sendPackets(new S_ServerMessage(942)); // 對方的負重太重，無法再給予。
			return;
		}
		item = inv.tradeItem(item, count, targetInv);
		target.onGetItem(item);
		target.turnOnOffLight();
		pc.turnOnOffLight();

		L1PetType petType = PetTypeTable.getInstance().get(target.getNpcTemplate().get_npcId());
		if ((petType == null) || target.isDead()) {
			return;
		}

		if (item.getItemId() == petType.getItemIdForTaming()) {
			tamePet(pc, target);
		}
		if ((item.getItemId() == 40070) && petType.canEvolve()) {
			evolvePet(pc, target);
		}
	}

	private final static String receivableImpls[] = new String[]
	{ "L1Npc", // NPC
			"L1Monster", // 怪物
			"L1Guardian", // 妖精森林的守護者
			"L1Teleporter", // 傳送師
			"L1Guard" }; // 警衛

	private boolean isNpcItemReceivable(L1Npc npc) {
		for (String impl : receivableImpls) {
			if (npc.getImpl().equals(impl)) {
				return true;
			}
		}
		return false;
	}

	private void tamePet(L1PcInstance pc, L1NpcInstance target) {
		if ((target instanceof L1PetInstance) || (target instanceof L1SummonInstance)) {
			return;
		}

		int petcost = 0;
		Object[] petlist = pc.getPetList().values().toArray();
		for (Object pet : petlist) {
			petcost += ((L1NpcInstance) pet).getPetcost();
		}
		int charisma = pc.getCha();
		if (pc.isCrown()) { // 王族
			charisma += 6;
		}
		else if (pc.isElf()) { // 妖精
			charisma += 12;
		}
		else if (pc.isWizard()) { // 法師
			charisma += 6;
		}
		else if (pc.isDarkelf()) { // 黑暗妖精
			charisma += 6;
		}
		else if (pc.isDragonKnight()) { // 龍騎士
			charisma += 6;
		}
		else if (pc.isIllusionist()) { // 幻術師
			charisma += 6;
		}
		charisma -= petcost;

		L1PcInventory inv = pc.getInventory();
		if ((charisma >= 6) && (inv.getSize() < 180)) {
			if (isTamePet(target)) {
				L1ItemInstance petamu = inv.storeItem(40314, 1); // 漂浮之眼的肉
				if (petamu != null) {
					new L1PetInstance(target, pc, petamu.getId());
					pc.sendPackets(new S_ItemName(petamu));
				}
			}
			else {
				pc.sendPackets(new S_ServerMessage(324)); // 馴養失敗。
			}
		}
	}

	private void evolvePet(L1PcInstance pc, L1NpcInstance target) {
		if (!(target instanceof L1PetInstance)) {
			return;
		}
		L1PcInventory inv = pc.getInventory();
		L1PetInstance pet = (L1PetInstance) target;
		L1ItemInstance petamu = inv.getItem(pet.getItemObjId());
		if ((pet.getLevel() >= 30) && // Lv30以上
				(pc == pet.getMaster()) && // 自分のペット
				(petamu != null)) {
			L1ItemInstance highpetamu = inv.storeItem(40316, 1);
			if (highpetamu != null) {
				pet.evolvePet( // 進化させる
				highpetamu.getId());
				pc.sendPackets(new S_ItemName(highpetamu));
				inv.removeItem(petamu, 1);
			}
		}
	}

	private boolean isTamePet(L1NpcInstance npc) {
		boolean isSuccess = false;
		int npcId = npc.getNpcTemplate().get_npcId();
		if (npcId == 45313) { // タイガー
			if ((npc.getMaxHp() / 3 > npc.getCurrentHp() // HPが1/3未満で1/16の確率
					)
					&& (Random.nextInt(16) == 15)) {
				isSuccess = true;
			}
		}
		else {
			if (npc.getMaxHp() / 3 > npc.getCurrentHp()) {
				isSuccess = true;
			}
		}

		if ((npcId == 45313) || (npcId == 45044) || (npcId == 45711)) { // タイガー、ラクーン、紀州犬の子犬
			if (npc.isResurrect()) { // RES後はテイム不可
				isSuccess = false;
			}
		}

		return isSuccess;
	}

	@Override
	public String getType() {
		return C_GIVE_ITEM;
	}
}
