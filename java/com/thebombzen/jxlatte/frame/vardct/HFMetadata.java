package com.thebombzen.jxlatte.frame.vardct;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.thebombzen.jxlatte.InvalidBitstreamException;
import com.thebombzen.jxlatte.frame.Frame;
import com.thebombzen.jxlatte.frame.group.LFGroup;
import com.thebombzen.jxlatte.frame.modular.ModularChannelInfo;
import com.thebombzen.jxlatte.frame.modular.ModularStream;
import com.thebombzen.jxlatte.io.Bitreader;
import com.thebombzen.jxlatte.util.IntPoint;
import com.thebombzen.jxlatte.util.MathHelper;

public class HFMetadata {
    public final int nbBlocks;
    public final TransformType[][] dctSelect;
    public final IntPoint[] blockList;
    public final int[][] hfMultiplier;
    public final ModularStream hfStream;

    public HFMetadata(Bitreader reader, LFGroup parent, Frame frame) throws IOException {
        IntPoint size = frame.getLFGroupSize(parent.lfGroupID).shift(-3);
        int n = MathHelper.ceilLog2(size.x * size.y);
        nbBlocks = 1 + reader.readBits(n);
        int aFromYWidth = MathHelper.ceilDiv(size.x, 8);
        int aFromYHeight = MathHelper.ceilDiv(size.y, 8);
        ModularChannelInfo xFromY = new ModularChannelInfo(aFromYWidth, aFromYHeight, 0, 0);
        ModularChannelInfo bFromY = new ModularChannelInfo(aFromYWidth, aFromYHeight, 0, 0);
        ModularChannelInfo blockInfo = new ModularChannelInfo(nbBlocks, 2, 0, 0);
        ModularChannelInfo sharpness = new ModularChannelInfo(size.x, size.y, 0, 0);
        hfStream = new ModularStream(reader, frame, 1 + 2*frame.getNumLFGroups() + parent.lfGroupID,
            new ModularChannelInfo[]{xFromY, bFromY, blockInfo, sharpness});
        hfStream.decodeChannels(reader);
        dctSelect = new TransformType[size.y][size.x];
        hfMultiplier = new int[size.y][size.x];
        int[][] blockInfoBuffer = hfStream.getDecodedBuffer()[2];
        List<IntPoint> blocks = new ArrayList<>();
        IntPoint lastBlock = new IntPoint();
        for (int i = 0; i < nbBlocks; i++) {
            int type = blockInfoBuffer[0][i];
            if (type > 26 || type < 0)
                throw new InvalidBitstreamException("Invalid Transform Type: " + type);
            blocks.add(placeBlock(lastBlock, TransformType.get(type), 1 + blockInfoBuffer[1][i]));
        }
        blockList = blocks.stream().toArray(IntPoint[]::new);
    }

    public String getBlockMapAsciiArt() {
        String[][] strings = new String[2 * dctSelect.length + 1][2 * dctSelect[0].length + 1];
        int k = 0;
        for (IntPoint block : blockList) {
            int dw = dctSelect[block.y][block.x].dctSelectWidth;
            int dh = dctSelect[block.y][block.x].dctSelectHeight;
            strings[2*block.y + 1][2*block.x + 1] = String.format("%03d", k++ % 1000);
            for (int x = 0; x < dw; x++) {
                strings[2*block.y][2*(block.x + x)] = "+";
                strings[2*block.y][2*(block.x + x) + 1] = "---";
                strings[2*(block.y+dh)][2*(block.x + x)] = "+";
                strings[2*(block.y+dh)][2*(block.x + x)+1] = "---";
            }
            for (int y = 0; y < dh; y++) {
                strings[2*(block.y + y)][2*block.x] = "+";
                strings[2*(block.y + y) + 1][2*block.x] = "|";
                strings[2*(block.y + y)][2*(block.x+dw)] = "+";
                strings[2*(block.y + y) + 1][2*(block.x+dw)] = "|";
            }
            strings[2*(block.y + dh)][2*(block.x + dw)] = "+";
        }
        StringBuilder builder = new StringBuilder();
        for (int y = 0; y < strings.length; y++) {
            for (int x = 0; x < strings[y].length; x++) {
                String s = strings[y][x];
                if (s == null) {
                    if (x % 2 == 0)
                        s = " ";
                    else
                        s = "   ";
                }
                builder.append(s);
            }
            builder.append(String.format("%n"));
        }
        return builder.toString();
    }

    private IntPoint placeBlock(IntPoint lastBlock, TransformType block, int mul) throws InvalidBitstreamException {
        for (; lastBlock.y < dctSelect.length; lastBlock.y++, lastBlock.x = 0) {
            int y = lastBlock.y;
            outer:
            for (; lastBlock.x < dctSelect[y].length; lastBlock.x++) {
                int x = lastBlock.x;
                // block too big, horizontally, to put here
                if (block.dctSelectWidth + x > dctSelect[y].length)
                    continue;
                // block too big, vertically, to put here
                if (block.dctSelectHeight + y > dctSelect.length)
                    continue;
                // space occupied
                for (int iy = 0; iy < block.dctSelectHeight; iy++) {
                    for (int ix = 0; ix < block.dctSelectWidth; ix++) {
                        if (dctSelect[y + iy][x + ix] != null)
                            continue outer;
                    }
                }
                for (int iy = 0; iy < block.dctSelectHeight; iy++)
                    Arrays.fill(dctSelect[y + iy], x, x + block.dctSelectWidth, block);
                hfMultiplier[y][x] = mul;
                return new IntPoint(x, y);
            }
        }
        throw new InvalidBitstreamException("Could not find place for block: " + block.type);
    }
}