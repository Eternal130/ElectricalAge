package mods.eln.transparentnode.autominer;

import com.dunk.tfc.Blocks.Devices.BlockChestTFC;
import mods.eln.Eln;
import mods.eln.item.ElectricalDrillDescriptor;
import mods.eln.item.MiningPipeDescriptor;
import mods.eln.item.electricalitem.OreColorMapping;
import mods.eln.misc.Coordinate;
import mods.eln.misc.INBTTReady;
import mods.eln.misc.Utils;
import mods.eln.ore.OreBlock;
import mods.eln.sim.IProcess;
import mods.eln.sixnode.lampsocket.LightBlockEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockOre;
import net.minecraft.block.BlockRedstoneOre;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.ArrayList;

public class AutoMinerSlowProcess implements IProcess, INBTTReady {

    private final AutoMinerElement miner;

    int pipeLength = 0;

    private double energyCounter = 0;
    private double energyTarget = 0;

    private boolean oneJobDone = true;
    boolean silkTouch = false;

    enum jobType {none, done, full, chestFull, ore, pipeAdd, pipeRemove}

    jobType job = jobType.none;
    private jobType oldJob = jobType.none;
    private final Coordinate jobCoord = new Coordinate();
    private int blinkCounter = 0;

    private int drillCount = 1;

    private ArrayList<ItemStack> itemsToDrop = new ArrayList<ItemStack>(4);

    public AutoMinerSlowProcess(AutoMinerElement autoMiner) {
        this.miner = autoMiner;
    }

    void toggleSilkTouch() {
        silkTouch = !silkTouch;
    }

    private boolean isReadyToDrill() {
        ElectricalDrillDescriptor drill = (ElectricalDrillDescriptor) ElectricalDrillDescriptor.getDescriptor(miner.getInventory().getStackInSlot(AutoMinerContainer.electricalDrillSlotId));
        if (drill == null) return false;
        return isStorageReady();
    }

    private boolean isStorageReady() {
        IInventory i = getDropInventory();
        if (i == null) return false;
        for (int idx = 0; idx < i.getSizeInventory(); idx++) {
            if (i.getStackInSlot(idx) == null)
                return true;
        }
        return false;
    }

    @Override
    public void process(double time) {
        ElectricalDrillDescriptor drill = (ElectricalDrillDescriptor) ElectricalDrillDescriptor.getDescriptor(miner.getInventory().getStackInSlot(AutoMinerContainer.electricalDrillSlotId));

        if (++blinkCounter >= 9) {
            blinkCounter = 0;
            if ((miner.inPowerLoad.getVoltage() / miner.descriptor.nominalVoltage - 0.5) * 3 > Math.random()) {
                miner.setPowerOk(true);
                LightBlockEntity.addLight(miner.lightCoordinate, 12, 11);
            } else {
                miner.setPowerOk(false);
            }
        }

        energyCounter += miner.powerResistor.getPower() * time;

        if (job != jobType.none && job != jobType.full && job != jobType.chestFull && job != jobType.done) {
            if (energyCounter >= energyTarget || (job == jobType.ore && !isReadyToDrill()) || !miner.powerOk) {
                setupJob();
            }

            if (energyCounter >= energyTarget) {
                oneJobDone = true;
                switch (job) {
                    case ore:
                        drillCount++;

                        Block block = jobCoord.world().getBlock(jobCoord.x, jobCoord.y, jobCoord.z);
                        int meta = jobCoord.world().getBlockMetadata(jobCoord.x, jobCoord.y, jobCoord.z);
                        if (silkTouch) {
                            itemsToDrop.add(new ItemStack(block, 1, meta));
                        } else {
                            itemsToDrop.addAll(block.getDrops(jobCoord.world(), jobCoord.x, jobCoord.y, jobCoord.z, meta, 0));
                        }

                        // Use cobblestone instead of air, everywhere except the mining shaft.
                        // This is so mobs won't spawn excessively.
                        int xDist = jobCoord.x - miner.node.coordinate.x, zDist = jobCoord.z - miner.node.coordinate.z;
                        if (xDist * xDist + zDist * zDist > 25) {
                            jobCoord.world().setBlock(jobCoord.x, jobCoord.y, jobCoord.z, Blocks.cobblestone);
                        } else {
                            jobCoord.world().setBlockToAir(jobCoord.x, jobCoord.y, jobCoord.z);
                        }

                        energyCounter -= energyTarget;
                        setupJob();
                        break;
                    case pipeAdd:
                        // miner.pushLog("Pipe " + (pipeLength + 1) + " added");
                        Eln.ghostManager.createGhost(jobCoord, miner.node.coordinate, jobCoord.y);
                        miner.getInventory().decrStackSize(AutoMinerContainer.MiningPipeSlotId, 1);

                        pipeLength++;
                        miner.needPublish();

                        energyCounter -= energyTarget;
                        setupJob();
                        break;
                    case pipeRemove:
                        // miner.pushLog("Pipe " + pipeLength + " removed");
                        Eln.ghostManager.removeGhostAndBlock(jobCoord);
                        if (miner.getInventory().getStackInSlot(AutoMinerContainer.MiningPipeSlotId) == null) {
                            miner.getInventory().setInventorySlotContents(AutoMinerContainer.MiningPipeSlotId, Eln.miningPipeDescriptor.newItemStack(1));
                        } else {
                            miner.getInventory().decrStackSize(AutoMinerContainer.MiningPipeSlotId, -1);
                        }

                        pipeLength--;
                        miner.needPublish();

                        energyCounter -= energyTarget;
                        setupJob();
                        break;
                    default:
                        break;
                }
            }
        } else {
            setupJob();
        }

        switch (job) {
            default:
                miner.powerResistor.highImpedance();
                break;
            case ore:
                if (drill == null) {
                    miner.powerResistor.highImpedance();
                } else {
                    double p = drill.nominalPower;
                    if (silkTouch) p *= 3;
                    miner.powerResistor.setResistance(Math.pow(miner.descriptor.nominalVoltage, 2.0) / p);
                }
                break;
            case pipeAdd:
                miner.powerResistor.setResistance(miner.descriptor.pipeOperationRp);
                break;
            case pipeRemove:
                miner.powerResistor.setResistance(miner.descriptor.pipeOperationRp);
                break;
        }

        if (oldJob != job) {
            miner.needPublish();
        }

        if (oneJobDone || oldJob != job) {
            switch (job) {
                case chestFull:
                    miner.pushLog("* Storage full!");
                    break;
                case done:
                    miner.pushLog("- SLEEP");
                    break;
                case full:
                    miner.pushLog("* Pipe stack full!");
                    break;
                case none:
                    miner.pushLog("* Waiting opcode.");
                    break;
                case ore:
                    miner.pushLog("- DRILL #" + drillCount);
                    break;
                case pipeAdd:
                    miner.pushLog("- ADD PIPE #" + (pipeLength + 1));
                    break;
                case pipeRemove:
                    miner.pushLog("- REMOVE PIPE #" + (pipeLength));
                    break;
                default:
                    break;
            }
        }
        oneJobDone = false;
        oldJob = job;
    }
    private IInventory getDropInventory() {
        IInventory inventoryEntity = null;
        Coordinate outputLocation = new Coordinate(1, -1, 0, miner.world());
        outputLocation.applyTransformation(miner.front, miner.coordinate());
        if (outputLocation.getTileEntity() instanceof IInventory) {
            inventoryEntity = (IInventory) outputLocation.getTileEntity();
            Block inventoryBlock = miner.world().getBlock(outputLocation.x, outputLocation.y, outputLocation.z);
            if(inventoryBlock instanceof BlockChestTFC) {
                IInventory possibleDoubleInventoryEntity = ((BlockChestTFC)inventoryBlock).getInventory(miner.world(),outputLocation.x, outputLocation.y, outputLocation.z);
                if (possibleDoubleInventoryEntity != null) {
                    inventoryEntity = possibleDoubleInventoryEntity;
                }
            }
        }
        return inventoryEntity;
    }

    private boolean drop(ItemStack stack) {
        return Utils.tryPutStackInInventory(stack, getDropInventory());
    }

    private boolean isMinable(Block block) {
        return block != Blocks.air
            && (block) != Blocks.flowing_water && (block) != Blocks.water
            && (block) != Blocks.flowing_lava && (block) != Blocks.lava
            && (block) != Blocks.obsidian && (block) != Blocks.bedrock;
    }

    private void setupJob() {
        ElectricalDrillDescriptor drill = (ElectricalDrillDescriptor) ElectricalDrillDescriptor.getDescriptor(miner.getInventory().getStackInSlot(AutoMinerContainer.electricalDrillSlotId));
        // OreScanner scanner = (OreScanner) ElectricalDrillDescriptor.getDescriptor(miner.inventory.getStackInSlot(AutoMinerContainer.OreScannerSlotId));
        MiningPipeDescriptor pipe = (MiningPipeDescriptor) ElectricalDrillDescriptor.getDescriptor(miner.getInventory().getStackInSlot(AutoMinerContainer.MiningPipeSlotId));

        int scannerRadius = Eln.instance.autominerRange;
        double scannerEnergy = 0;

        jobCoord.dimension = miner.node.coordinate.dimension;
        jobCoord.x = miner.node.coordinate.x;
        jobCoord.y = miner.node.coordinate.y - pipeLength;
        jobCoord.z = miner.node.coordinate.z;

        // Attempt to drop items. This might not be successful.
        while (itemsToDrop.size() > 0) {
            int index = itemsToDrop.size() - 1;
            if (drop(itemsToDrop.get(index))) {
                itemsToDrop.remove(index);
            }
        }

        boolean jobFind = false;
        if (!miner.node.coordinate.getBlockExist()) {
            setJob(jobType.none);
        } else if (!miner.powerOk) {
            setJob(jobType.none);
        } else if (drill == null) {
            if (jobCoord.y != miner.node.coordinate.y) {
                ItemStack pipeStack = miner.getInventory().getStackInSlot(AutoMinerContainer.MiningPipeSlotId);
                if (pipeStack == null || (pipeStack.stackSize != pipeStack.getMaxStackSize() && pipeStack.stackSize != miner.getInventory().getInventoryStackLimit())) {
                    jobFind = true;
                    setJob(jobType.pipeRemove);
                } else {
                    jobFind = true;
                    setJob(jobType.full);
                }
            }
        } else if (!isStorageReady() || itemsToDrop.size() != 0) {
            setJob(jobType.chestFull);
            jobFind = true;
        } else if (pipe != null) {
            if (jobCoord.y < miner.node.coordinate.y - 2) {
                int depth = (miner.node.coordinate.y - jobCoord.y);
                double miningRay = depth / 10.0 + 0.1;
                miningRay = Math.min(miningRay, 2);
                if (depth < scannerRadius) scannerRadius = depth + 1;
                miningRay = Math.min(miningRay, scannerRadius - 2);
                for (jobCoord.z = miner.node.coordinate.z - scannerRadius; jobCoord.z <= miner.node.coordinate.z + scannerRadius; jobCoord.z++) {
                    for (jobCoord.x = miner.node.coordinate.x - scannerRadius; jobCoord.x <= miner.node.coordinate.x + scannerRadius; jobCoord.x++) {
                        double dx = jobCoord.x - miner.node.coordinate.x;
                        double dy = 0;
                        double dz = jobCoord.z - miner.node.coordinate.z;
                        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        Block block = jobCoord.world().getBlock(jobCoord.x, jobCoord.y, jobCoord.z);
                        if (checkIsOre(jobCoord) || (distance > 0.1 && distance < miningRay && isMinable(block))) {
                            jobFind = true;
                            setJob(jobType.ore);
                            break;
                        }
                    }
                    if (jobFind) break;
                }
            }

            if (!jobFind) {
                if (jobCoord.y < 3) {
                    jobFind = true;
                    setJob(jobType.done);
                } else {
                    jobCoord.x = miner.node.coordinate.x;
                    jobCoord.y--;
                    jobCoord.z = miner.node.coordinate.z;

                    Block block = jobCoord.world().getBlock(jobCoord.x, jobCoord.y, jobCoord.z);
                    if (block != Blocks.air
                        && block != Blocks.flowing_water && block != Blocks.water
                        && block != Blocks.flowing_lava && block != Blocks.lava) {
                        if (block != Blocks.obsidian && block != Blocks.bedrock) {
                            jobFind = true;
                            setJob(jobType.ore);
                        } else {
                            jobFind = true;
                            setJob(jobType.done);
                        }
                    } else {
                        jobFind = true;
                        setJob(jobType.pipeAdd);
                    }
                }
            }
        }
        if (!jobFind) setJob(jobType.none);

        switch (job) {
            case ore:
                energyTarget = drill.OperationEnergy + scannerEnergy;
                // Copied from Mekanism. Note that the power demand is tripled, so in effect this doubles time.
                if (silkTouch) energyTarget *= 6;
                break;
            case pipeAdd:
                energyTarget = miner.descriptor.pipeOperationEnergy;
                break;
            case pipeRemove:
                energyTarget = miner.descriptor.pipeOperationEnergy;
                break;
            default:
                energyTarget = 0;
                break;
        }
    }

    private void setJob(jobType job) {
        if (job != this.job) {
            miner.needPublish();
            energyCounter = 0;
        }
        this.job = job;
    }

    private boolean checkIsOre(Coordinate coordinate) {
        Block block = coordinate.world().getBlock(coordinate.x, coordinate.y, coordinate.z);
        if (block instanceof BlockOre) return true;
        if (block instanceof OreBlock) return true;
        if (block instanceof BlockRedstoneOre) return true;
        return OreColorMapping.INSTANCE.getMap()[Block.getIdFromBlock(block) + (coordinate.world().getBlockMetadata(coordinate.x, coordinate.y, coordinate.z) << 12)] != 0;
    }

    public void onBreakElement() {
        destroyPipe();
    }

    private void destroyPipe() {
        dropPipe();
        Eln.ghostManager.removeGhostAndBlockWithObserverAndNotUuid(miner.node.coordinate, miner.descriptor.getGhostGroupUuid());
        pipeLength = 0;
        miner.needPublish();
    }

    private void dropPipe() {
        Coordinate coord = new Coordinate(miner.node.coordinate);
        for (coord.y = miner.node.coordinate.y - 1; coord.y >= miner.node.coordinate.y - pipeLength; coord.y--) {
            Utils.dropItem(Eln.miningPipeDescriptor.newItemStack(1), coord);
        }
    }

    void ghostDestroyed() {
        destroyPipe();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt, String str) {
        pipeLength = nbt.getInteger(str + "AMSP" + "pipeLength");
        drillCount = nbt.getInteger(str + "AMSP" + "drillCount");
        if (drillCount == 0) drillCount++;
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt, String str) {
        nbt.setInteger(str + "AMSP" + "pipeLength", pipeLength);
        nbt.setInteger(str + "AMSP" + "drillCount", drillCount);
    }
}
