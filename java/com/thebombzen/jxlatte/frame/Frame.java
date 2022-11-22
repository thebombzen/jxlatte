package com.thebombzen.jxlatte.frame;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntUnaryOperator;

import com.thebombzen.jxlatte.InvalidBitstreamException;
import com.thebombzen.jxlatte.Options;
import com.thebombzen.jxlatte.bundle.ImageHeader;
import com.thebombzen.jxlatte.entropy.EntropyStream;
import com.thebombzen.jxlatte.frame.features.noise.NoiseGroup;
import com.thebombzen.jxlatte.frame.features.spline.Spline;
import com.thebombzen.jxlatte.frame.group.LFGroup;
import com.thebombzen.jxlatte.frame.group.Pass;
import com.thebombzen.jxlatte.frame.group.PassGroup;
import com.thebombzen.jxlatte.frame.modular.MATree;
import com.thebombzen.jxlatte.frame.modular.ModularChannel;
import com.thebombzen.jxlatte.frame.modular.ModularChannelInfo;
import com.thebombzen.jxlatte.frame.vardct.HFGlobal;
import com.thebombzen.jxlatte.frame.vardct.HFPass;
import com.thebombzen.jxlatte.io.Bitreader;
import com.thebombzen.jxlatte.util.FlowHelper;
import com.thebombzen.jxlatte.util.IntPoint;
import com.thebombzen.jxlatte.util.MathHelper;
import com.thebombzen.jxlatte.util.TaskList;
import com.thebombzen.jxlatte.util.functional.ExceptionalFunction;
import com.thebombzen.jxlatte.util.functional.FunctionalHelper;

public class Frame {

    public static final int[] cMap = new int[]{1, 0, 2};

    private static IntPoint[] epfCross = new IntPoint[] {
        IntPoint.ZERO,
        new IntPoint(0, 1), new IntPoint(1, 0),
        new IntPoint(-1, 0), new IntPoint(0, -1),
    };

    private static IntPoint[] epfDoubleCross = new IntPoint[] {
        IntPoint.ZERO,
        new IntPoint(-1, 0), new IntPoint(0, -1), new IntPoint(1, 0), new IntPoint(0, 1),
        new IntPoint(1, -1), new IntPoint(1, 1), new IntPoint(-1, -1), new IntPoint(-1, 1),
        new IntPoint(2, 0), new IntPoint(0, 2), new IntPoint(-2, 0), new IntPoint(-2, 2),
    };

    private static float[][] laplacian = null;

    private Bitreader globalReader;
    private FrameHeader header;
    private int numGroups;
    private int numLFGroups;

    private CompletableFuture<byte[]>[] buffers;
    private int[] tocPermuation;
    private int[] tocLengths;
    private LFGlobal lfGlobal;
    public final ImageHeader globalMetadata;
    private boolean permutedTOC;
    private float[][][] buffer;
    private float[][][] noiseBuffer;
    private Pass[] passes;
    private boolean decoded = false;
    private HFGlobal hfGlobal;
    private LFGroup[] lfGroups;
    private int groupRowStride;
    private int lfGroupRowStride;
    private MATree globalTree;
    private int width;
    private int height;

    public Frame(Bitreader reader, ImageHeader globalMetadata) {
        this.globalReader = reader;
        this.globalMetadata = globalMetadata;
    }

    public Frame(Frame frame) {
        this(frame, true);
    }

    public Frame(Frame frame, boolean copyBuffer) {
        if (!frame.decoded)
            throw new IllegalArgumentException();
        this.decoded = frame.decoded;
        this.header = new FrameHeader(frame.header);
        this.numGroups = frame.numGroups;
        this.numLFGroups = frame.numLFGroups;
        this.tocPermuation = frame.tocPermuation;
        this.tocLengths = frame.tocLengths;
        this.lfGlobal = frame.lfGlobal;
        this.globalMetadata = frame.globalMetadata;
        this.permutedTOC = frame.permutedTOC;
        this.buffer = new float[frame.buffer.length][][];
        for (int c = 0; c < buffer.length; c++) {
            if (copyBuffer) {
                buffer[c] = new float[frame.buffer[c].length][];
            } else {
                buffer[c] = new float[globalMetadata.getSize().height][];
            }
            for (int y = 0; y < buffer[c].length; y++) {
                if (copyBuffer)
                    buffer[c][y] = Arrays.copyOf(frame.buffer[c][y], frame.buffer[c][y].length);
                else
                    buffer[c][y] = new float[globalMetadata.getSize().width];
            }
        }
        if (!copyBuffer) {
            header.width = globalMetadata.getSize().width;
            header.height = globalMetadata.getSize().height;
            header.origin = new IntPoint();
        }
    }

    public void readHeader() throws IOException {
        globalReader.zeroPadToByte();
        this.header = new FrameHeader(globalReader, this.globalMetadata);
        width = header.width;
        height = header.height;
        width = MathHelper.ceilDiv(width, header.upsampling);
        height = MathHelper.ceilDiv(height, header.upsampling);
        width = MathHelper.ceilDiv(width, 1 << (3 * header.lfLevel));
        height = MathHelper.ceilDiv(height, 1 << (3 * header.lfLevel));
        groupRowStride = MathHelper.ceilDiv(width, header.groupDim);
        lfGroupRowStride = MathHelper.ceilDiv(width, header.groupDim << 3);
        numGroups = groupRowStride * MathHelper.ceilDiv(height, header.groupDim);
        numLFGroups = lfGroupRowStride * MathHelper.ceilDiv(height, header.groupDim << 3);
        readTOC();
    }

    public FrameHeader getFrameHeader() {
        return header;
    }

    public void skipFrameData() throws IOException {
        for (int i = 0; i < tocLengths.length; i++) {
            globalReader.skipBits(tocLengths[i]);
        }
    }

    private void readTOC() throws IOException {
        int tocEntries;
        if (numGroups == 1 && header.passes.numPasses == 1) {
            tocEntries = 1;
        } else {
            // lfGlobal + one per LfGroup + HfGlobal + HfPassData
            tocEntries = 1 + numLFGroups + 1 + numGroups * header.passes.numPasses;
        }

        permutedTOC = globalReader.readBool();
        if (permutedTOC) {
            EntropyStream tocStream = new EntropyStream(globalReader, 8);
            tocPermuation = readPermutation(globalReader, tocStream, tocEntries, 0);
            if (!tocStream.validateFinalState())
                throw new InvalidBitstreamException("Invalid final ANS state decoding TOC");
        } else {
            tocPermuation = new int[tocEntries];
            for (int i = 0; i < tocEntries; i++)
                tocPermuation[i] = i;
        }

        globalReader.zeroPadToByte();
        tocLengths = new int[tocEntries];

        {
            // if we don't declare a new variable here with the unchecked assignment
            // it won't compile, for some reason
            @SuppressWarnings("unchecked")
            CompletableFuture<byte[]>[] buffers = new CompletableFuture[tocEntries];
            this.buffers = buffers;
        }

        for (int i = 0; i < tocEntries; i++)
            buffers[i] = new CompletableFuture<>();

        for (int i = 0; i < tocEntries; i++)
            tocLengths[i] = globalReader.readU32(0, 10, 1024, 14, 17408, 22, 4211712, 30);

        globalReader.zeroPadToByte();

        if (tocEntries != 1) {
            new Thread(() -> {
                for (int i = 0; i < tocEntries; i++) {
                    try {
                        byte[] buffer = readBuffer(i);
                        buffers[i].complete(buffer);
                    } catch (Throwable ex) {
                        buffers[i].completeExceptionally(ex);
                    }
                }
            }).start();
        }
    }

    private byte[] readBuffer(int index) throws IOException {
        int length = tocLengths[index];
        byte[] buffer = new byte[length + 4];
        int read = globalReader.read(buffer, 0, length);
        if (read < length)
            throw new EOFException("Unable to read full TOC entry");
        return buffer;
    }

    private CompletableFuture<Bitreader> getBitreader(int index) throws IOException {
        if (tocLengths.length == 1)
            return CompletableFuture.completedFuture(this.globalReader);
        int permutedIndex = tocPermuation[index];
        return buffers[permutedIndex].thenApply((buff) -> {
            return new Bitreader(new ByteArrayInputStream(buff));
        });
    }

    public static int[] readPermutation(Bitreader reader, EntropyStream stream, int size, int skip) throws IOException {
        IntUnaryOperator ctx = x -> Math.min(7, MathHelper.ceilLog1p(x));
        int end = stream.readSymbol(reader, ctx.applyAsInt(size));
        if (end > size - skip)
            throw new InvalidBitstreamException("Illegal end value in lehmer sequence");
        int[] lehmer = new int[size];
        for (int i = skip; i < end + skip; i++) {
            lehmer[i] = stream.readSymbol(reader, ctx.applyAsInt(i > skip ? lehmer[i - 1] : 0));
            if (lehmer[i] >= size - i)
                throw new InvalidBitstreamException("Illegal lehmer value in lehmer sequence");
        }
        List<Integer> temp = new LinkedList<Integer>();
        int[] permutation = new int[size];
        for (int i = 0; i < size; i++)
            temp.add(i);
        for (int i = 0; i < size; i++) {
            int index = lehmer[i];
            permutation[i] = temp.remove(index);
        }
        return permutation;
    }

    private float[][] performUpsampling(float[][] buffer, int c) {
        int color;
        if (header.encoding == FrameFlags.VARDCT)
            color = 3;
        else
            color = globalMetadata.getColorChannelCount();
        int k;
        if (c < color)
            k = header.upsampling;
        else
            k = header.ecUpsampling[c - color];
        if (k == 1)
            return buffer;
        int l = MathHelper.ceilLog1p(k - 1) - 1;
        float[][][][] upWeights = globalMetadata.getUpWeights()[l];
        float[][] newBuffer = new float[buffer.length * k][];
        TaskList<Void> tasks = new TaskList<>();
        for (int y0 = 0; y0 < buffer.length; y0++) {
            tasks.submit(y0, (y) -> {
                for (int ky = 0; ky < k; ky++) {
                    newBuffer[y*k + ky] = new float[buffer[y].length * k];
                    for (int x = 0; x < buffer[y].length; x++) {
                        for (int kx = 0; kx < k; kx++) {
                            float[][] weights = upWeights[ky][kx];
                            float total = 0f;
                            float min = Float.MAX_VALUE;
                            float max = Float.MIN_VALUE;
                            for (int iy = 0; iy < 5; iy++) {
                                for (int ix = 0; ix < 5; ix++) {
                                    int newY = MathHelper.mirrorCoordinate(y + iy - 2, buffer.length);
                                    int newX = MathHelper.mirrorCoordinate(x + ix - 2, buffer[newY].length);
                                    float sample = buffer[newY][newX];
                                    if (sample < min)
                                        min = sample;
                                    if (sample > max)
                                        max = sample;
                                    total += weights[iy][ix] * sample;
                                }
                            }
                            newBuffer[y*k + ky][x*k + kx] = MathHelper.clamp(total, min, max);
                        }
                    }
                }
            });
        }
        tasks.collect();
        return newBuffer;
    }

    private void decodeLFGroups(TaskList<?> tasks) throws IOException {

        List<ModularChannelInfo> lfReplacementChannels = new ArrayList<>();
        List<Integer> lfReplacementChannelIndicies = new ArrayList<>();
        for (int i = 0; i < lfGlobal.gModular.stream.getEncodedChannelCount(); i++) {
            ModularChannel chan = lfGlobal.gModular.stream.getChannel(i);
            if (!chan.isDecoded()) {
                if (chan.hshift >= 3 && chan.vshift >= 3) {
                    lfReplacementChannelIndicies.add(i);
                    IntPoint size = new IntPoint(header.lfGroupDim).shiftRight(chan.hshift, chan.vshift);
                    lfReplacementChannels.add(new ModularChannelInfo(size.x, size.y, chan.hshift, chan.vshift));
                }
            }
        }

        lfGroups = new LFGroup[numLFGroups];

        for (int lfGroupID = 0; lfGroupID < numLFGroups; lfGroupID++) {
            Bitreader reader = FunctionalHelper.join(getBitreader(1 + lfGroupID));
            IntPoint lfGroupPos = IntPoint.coordinates(lfGroupID, lfGroupRowStride);
            ModularChannelInfo[] replaced = lfReplacementChannels.stream().map(ModularChannelInfo::new)
                .toArray(ModularChannelInfo[]::new);
            IntPoint frameSize = getPaddedFrameSize();
            for (ModularChannelInfo info : replaced) {
                IntPoint shift = new IntPoint(info.hshift, info.vshift);
                IntPoint lfSize = frameSize.ceilDiv(IntPoint.ONE.shiftLeft(shift));
                IntPoint chanSize = new IntPoint(info.width, info.height);
                IntPoint pos = lfGroupPos.times(chanSize);
                IntPoint size = chanSize.min(lfSize.minus(pos));
                info.width = size.x;
                info.height = size.y;
                info.origin = pos;
            }
            lfGroups[lfGroupID] = new LFGroup(reader, this, lfGroupID, replaced);
        }

        /* populate decoded LF Groups */
        for (int lfGroupID = 0; lfGroupID < numLFGroups; lfGroupID++) {
            for (int j = 0; j < lfReplacementChannelIndicies.size(); j++) {
                int index = lfReplacementChannelIndicies.get(j);
                ModularChannel channel = lfGlobal.gModular.stream.getChannel(index);
                int[][] newChannel = lfGroups[lfGroupID].modularLFGroupBuffer[j];
                ModularChannelInfo newChannelInfo = lfGroups[lfGroupID].modularLFGroupInfo[j];
                FlowHelper.parallelIterate(IntPoint.sizeOf(newChannel), (x, y) -> {
                    channel.set(x + newChannelInfo.origin.x, y + newChannelInfo.origin.y, newChannel[y][x]);
                });
            }
        }
    }

    private void decodePasses(Bitreader reader, TaskList<?> tasks) throws IOException {
        passes = new Pass[header.passes.numPasses];
        for (int pass = 0; pass < passes.length; pass++) {
            passes[pass] = new Pass(reader, this, pass, pass > 0 ? passes[pass - 1].minShift : 0);
        }
    }

    private void decodePassGroups(TaskList<?> tasks) throws IOException {

        int numPasses = passes.length;
        PassGroup[][] passGroups = new PassGroup[numPasses][numGroups];

        for (int pass = 0; pass < numPasses; pass++) {
            for (int group = 0; group < numGroups; group++) {
                Bitreader reader = FunctionalHelper.join(getBitreader(2 + numLFGroups + pass * numGroups + group));
                ModularChannelInfo[] replaced = Arrays.asList(passes[pass].replacedChannels).stream()
                    .filter(Objects::nonNull).map(ModularChannelInfo::new).toArray(ModularChannelInfo[]::new);
                for (ModularChannelInfo info : replaced) {
                    IntPoint shift = new IntPoint(info.hshift, info.vshift);
                    IntPoint passGroupSize = new IntPoint(header.groupDim).shiftRight(shift);
                    int rowStride = MathHelper.ceilDiv(info.width, passGroupSize.x);
                    IntPoint pos = IntPoint.coordinates(group, rowStride).times(passGroupSize);
                    IntPoint chanSize = new IntPoint(info.width, info.height);
                    info.origin = pos;
                    IntPoint size = passGroupSize.min(chanSize.minus(info.origin));
                    info.width = size.x;
                    info.height = size.y;
                }
                passGroups[pass][group] = new PassGroup(reader, Frame.this, pass, group, replaced);
            }
        }

        for (int pass = 0; pass < numPasses; pass++) {
            int j = 0;
            for (int i = 0; i < passes[pass].replacedChannels.length; i++) {
                if (passes[pass].replacedChannels[i] == null)
                    continue;
                ModularChannel channel = lfGlobal.gModular.stream.getChannel(i);
                for (int group = 0; group < numGroups; group++) {
                    ModularChannelInfo newChannelInfo = passGroups[pass][group].modularPassGroupInfo[j];
                    int[][] buff = passGroups[pass][group].modularPassGroupBuffer[j];
                    FlowHelper.parallelIterate(new IntPoint(newChannelInfo.width, newChannelInfo.height), (x, y) -> {
                        channel.set(x + newChannelInfo.origin.x, y + newChannelInfo.origin.y, buff[y][x]);
                    });
                }
                j++;
            }
        }

        if (header.encoding == FrameFlags.VARDCT) {
            for (int pass = 0; pass < numPasses; pass++) {
                for (int group = 0; group < numGroups; group++) {
                    PassGroup passGroup = passGroups[pass][group];
                    PassGroup prev = pass > 0 ? passGroups[pass - 1][group] : null;
                    passGroup.invertVarDCT(buffer, prev);
                }
            }
        }
    }

    public void decodeFrame() throws IOException {
        if (this.decoded)
            return;
        this.decoded = true;

        long time0 = System.nanoTime() / 1000000L;

        lfGlobal = FunctionalHelper.join(getBitreader(0)
            .thenApplyAsync(ExceptionalFunction.of((reader) -> new LFGlobal(reader, this))));
        IntPoint paddedSize = getPaddedFrameSize();
        // VarDCT always has 3 channels even in grayscale
        buffer = new float[(header.encoding == FrameFlags.VARDCT ? 3 : globalMetadata.getColorChannelCount())
            + globalMetadata.getExtraChannelCount()][paddedSize.y][paddedSize.x];
        TaskList<Void> tasks = new TaskList<>();

        long time1 = System.nanoTime() / 1000000L;

        decodeLFGroups(tasks);

        long time2 = System.nanoTime() / 1000000L;

        Bitreader hfGlobalReader = FunctionalHelper.join(getBitreader(1 + numLFGroups));
        if (header.encoding == FrameFlags.VARDCT)
            hfGlobal = new HFGlobal(hfGlobalReader, this);
        else
            hfGlobal = null;

        long time3 = System.nanoTime() / 1000000L;

        decodePasses(hfGlobalReader, tasks);

        long time4 = System.nanoTime() / 1000000L;

        decodePassGroups(tasks);

        long time5 = System.nanoTime() / 1000000L;

        lfGlobal.gModular.stream.applyTransforms();
        int[][][] modularBuffer = lfGlobal.gModular.stream.getDecodedBuffer();

        for (int c = 0; c < modularBuffer.length; c++) {
            int cIn = c;
            float scaleFactor;
            boolean xybM = globalMetadata.isXYBEncoded() && header.encoding == FrameFlags.MODULAR;
            // X, Y, B is encoded as Y, X, (B - Y)
            int cOut = (xybM ? cMap[c] : c) + buffer.length - modularBuffer.length;
            if (xybM)
                scaleFactor = lfGlobal.lfDequant[cOut];
            else if (globalMetadata.getBitDepthHeader().expBits != 0 || header.encoding == FrameFlags.VARDCT)
                scaleFactor = 1.0f;
            else
                scaleFactor = 1.0f / ~(~0L << globalMetadata.getBitDepthHeader().bitsPerSample);
            FlowHelper.parallelIterate(new IntPoint(width, height), (x, y) -> {
                // X, Y, B is encoded as Y, X, (B - Y)
                if (xybM && cIn == 2)
                    buffer[cOut][y][x] = scaleFactor * (modularBuffer[0][y][x] + modularBuffer[2][y][x]);
                else
                    buffer[cOut][y][x] = scaleFactor * modularBuffer[cIn][y][x];
            });
        }

        invertSubsampling();

        if (header.restorationFilter.gab) {
            performGabConvolution();
        }

        if (header.restorationFilter.epfIterations > 0) {
            performEdgePreservingFilter();
        }

        long time6 = System.nanoTime() / 1000000L;

        System.err.format(" LFGLobal: %d ms%n LF Groups: %d ms%n HF Global: %d ms%n Pass: %d ms%n Pass Groups: %d ms%n Restoration: %d ms%n",
            time1 - time0, time2 - time1, time3 - time2, time4 - time3, time5 - time4, time6 - time5);
    }

    private void performGabConvolution() {
        float[][] normGabW = new float[3][3];
        for (int c = 0; c < 3; c++) {
            float gabW1 = header.restorationFilter.gab1Weights[c];
            float gabW2 = header.restorationFilter.gab2Weights[c];
            float mult = 1f / (1f + 4f * (gabW1 + gabW2));
            normGabW[0][c] = mult;
            normGabW[1][c] = gabW1 * mult;
            normGabW[2][c] = gabW2 * mult;
        }
        for (int c : Frame.cMap) {
            IntPoint size = IntPoint.sizeOf(buffer[c]);
            float[][] newBuffer = new float[size.y][size.x];
            FlowHelper.parallelIterate(size, (x, y) -> {
                int west = MathHelper.mirrorCoordinate(x - 1, size.x);
                int east = MathHelper.mirrorCoordinate(x + 1, size.x);
                int north = MathHelper.mirrorCoordinate(y - 1, size.y);
                int south = MathHelper.mirrorCoordinate(y + 1, size.y);
                float adjacent = buffer[c][y][west] + buffer[c][y][east]
                    + buffer[c][north][x] + buffer[c][south][x];
                float diagonal = buffer[c][north][west] + buffer[c][north][east]
                    + buffer[c][south][west] + buffer[c][south][east];
                newBuffer[y][x] = normGabW[0][c] * buffer[c][y][x]
                    + normGabW[1][c] * adjacent + normGabW[2][c] * diagonal;
            });
            buffer[c] = newBuffer;
        }
    }

    private void performEdgePreservingFilter() {
        float stepMultiplier = (float)(1.65D * 4D * (1D - MathHelper.SQRT_H));

        IntPoint size = getPaddedFrameSize();
        float[][] sigma = new float[size.y >> 3][size.x >> 3];
        
        FlowHelper.parallelIterate(size.shiftRight(3), (bp) -> {
            if (header.encoding == FrameFlags.VARDCT) {
                IntPoint lfPosInFrame = bp.shiftRight(header.logLFGroupDim - 3);
                IntPoint blockPosInLFGroup = bp.minus(lfPosInFrame.shiftLeft(header.logLFGroupDim - 3));
                LFGroup lfg = lfGroups[lfPosInFrame.unwrapCoord(lfGroupRowStride)];
                int hf = blockPosInLFGroup.get(lfg.hfMetadata.blockMap).hfMult();
                int sharpness = blockPosInLFGroup.get(lfg.hfMetadata.hfStreamBuffer[3]);
                if (sharpness < 0 || sharpness > 7)
                    throw new InvalidBitstreamException("Invalid EPF Sharpness: " + sharpness);
                sigma[bp.y][bp.x] = hf * header.restorationFilter.epfQuantMul
                    * header.restorationFilter.epfSharpLut[sharpness];
            } else {
                sigma[bp.y][bp.x] = header.restorationFilter.epfSigmaForModular;
            }
        });

        float[][][] outputBuffer = new float[3][size.y][size.x];

        for (int i : FlowHelper.range(3)) {

            if (i == 0 && header.restorationFilter.epfIterations < 3)
                continue;

            if (i == 2 && header.restorationFilter.epfIterations < 2)
                break;

            float sigmaScale = stepMultiplier * (i == 0 ? header.restorationFilter.epfPass0SigmaScale :
                i == 1 ? 1.0f : header.restorationFilter.epfPass2SigmaScale);

            FlowHelper.parallelIterate(size, (p) -> {
                float sumWeights = 0f;
                float[] sumChannels = new float[3];
                float s = p.shiftRight(3).get(sigma);
                if (s < 0.3) {
                    for (int c = 0; c < 3; c++) {
                        outputBuffer[c][p.y][p.x] = buffer[c][p.y][p.x];
                    }
                    return;
                }
                for (IntPoint ip : (i == 0 ? epfDoubleCross : epfCross)) {
                    IntPoint np = p.plus(ip).mirrorCoordinate(size);
                    float dist = i == 2 ? epfDistance2(buffer, p, np, size) : epfDistance1(buffer, p, np, size);
                    float weight = epfWeight(sigmaScale, dist, s, p);
                    sumWeights += weight;
                    for (int c = 0; c < 3; c++) {
                        sumChannels[c] += np.get(buffer[c]) * weight;
                    }
                }
                for (int c = 0; c < 3; c++) {
                    p.set(outputBuffer[c], sumChannels[c] / sumWeights);
                }
            });

            for (int c = 0; c < 3; c++) {
                /* swapping lets us re-use the output buffer without re-allocing */
                float[][] tmp = buffer[c];
                buffer[c] = outputBuffer[c];
                outputBuffer[c] = tmp;
            }
        }
    }

    private float epfDistance1(float[][][] buffer, IntPoint basePos, IntPoint distPos, IntPoint size) {
        float dist = 0;
        for (int c = 0; c < 3; c++) {
            for (IntPoint p : epfCross) {
                dist += Math.abs(basePos.plus(p).mirrorCoordinate(size).get(buffer[c])
                    - distPos.plus(p).mirrorCoordinate(size).get(buffer[c]))
                    * header.restorationFilter.epfChannelScale[c];
            }
        }

        return dist;
    }

    private float epfDistance2(float[][][] buffer, IntPoint basePos, IntPoint distPos, IntPoint size) {
        float dist = 0;
        for (int c = 0; c < 3; c++) {
            dist += Math.abs(basePos.mirrorCoordinate(size).get(buffer[c])
                    - distPos.mirrorCoordinate(size).get(buffer[c]))
                    * header.restorationFilter.epfChannelScale[c];
        }
        return dist;
    }

    private float epfWeight(float stepMultiplier, float distance, float sigma, IntPoint ref) {
        int modX = ref.x & 0b111;
        int modY = ref.y & 0b111;
        if (modX == 0 || modX == 7 || modY == 0 || modY == 7) {
            distance *= header.restorationFilter.epfBorderSadMul;
        }
        float v = 1f - distance * stepMultiplier / sigma;
        if (v <= 0)
            return 0f;

        return v;
    }

    private void invertSubsampling() {
        for (int c = 0; c < 3; c++) {
            int xShift = header.jpegUpsampling[c].x;
            int yShift = header.jpegUpsampling[c].y;
            while (xShift-- > 0) {
                float[][] newBuffer = new float[buffer[c].length][];
                for (int y = 0; y < buffer[c].length; y++) {
                    newBuffer[y] = new float[2 * buffer[c][y].length];
                    for (int x = 0; x < buffer[c][y].length; x++) {
                        float b75 = 0.75f * buffer[c][y][x];
                        newBuffer[y][2*x] = b75 + 0.25f * buffer[c][y][Math.max(0, x - 1)];
                        newBuffer[y][2*x + 1] = b75 + 0.25f * buffer[c][y][Math.min(buffer[c][y].length - 1, x + 1)];
                    }
                }
                buffer[c] = newBuffer;
            }
            while (yShift-- > 0) {
                float[][] newBuffer = new float[2 * buffer[c].length][];
                for (int y = 0; y < buffer[c].length; y++) {
                    newBuffer[y] = new float[buffer[c][y].length];
                    for (int x = 0; x < buffer[c][y].length; x++) {
                        float b75 = 0.75f * buffer[c][y][x];
                        newBuffer[2*y][x] = b75 + 0.25f * buffer[c][Math.max(0, y - 1)][x];
                        newBuffer[2*y + 1][x] = b75 + 0.25f * buffer[c][Math.min(buffer[c].length - 1, y + 1)][x];
                    }
                }
                buffer[c] = newBuffer;
            }
        }
    }

    public void upsample() {
        for (int c = 0; c < buffer.length; c++)
            buffer[c] = performUpsampling(buffer[c], c);
        width *= header.upsampling;
        height *= header.upsampling;
        header.origin = header.origin.times(header.upsampling);
    }

    public void renderSplines() {
        if (lfGlobal.splines == null)
            return;
        for (int s = 0; s < lfGlobal.splines.numSplines; s++) {
            Spline spline = new Spline(s, lfGlobal.splines.controlPoints[s]);
            spline.renderSpline(this);
        }
    }

    public void initializeNoise(long seed0) {
        if (lfGlobal.noiseParameters == null)
            return;
        int rowStride = MathHelper.ceilDiv(header.width, header.groupDim);
        float[][][] noiseBuffer = new float[3][header.height][header.width];
        int numGroups = rowStride * MathHelper.ceilDiv(header.height, header.groupDim);
        TaskList<Void> tasks = new TaskList<>();
        for (int group = 0; group < numGroups; group++) {
            IntPoint groupXYUp = IntPoint.coordinates(group, rowStride).times(header.upsampling);
            // SPEC: spec doesn't mention how noise groups interact with upsampling
            for (int iy = 0; iy < header.upsampling; iy++) {
                for (int ix = 0; ix < header.upsampling; ix++) {
                    int x0 = (groupXYUp.x + ix) * header.groupDim;
                    int y0 = (groupXYUp.y + iy) * header.groupDim;
                    tasks.submit(() -> new NoiseGroup(header, seed0, noiseBuffer, x0, y0));
                }
            }
        }

        if (laplacian == null) {
            laplacian = new float[5][5];
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < 5; j++) {
                    laplacian[i][j] = (i == 2 && j == 2 ? -3.84f : 0.16f);
                }
            }
        }

        this.noiseBuffer = new float[3][header.height][header.width];
        tasks.collect();

        FlowHelper.parallelIterate(3, new IntPoint(header.width, header.height), (c, x, y) -> {
            for (int iy = 0; iy < 5; iy++) {
                for (int ix = 0; ix < 5; ix++) {
                    int cy = MathHelper.mirrorCoordinate(y + iy - 2, header.height);
                    int cx = MathHelper.mirrorCoordinate(x + ix - 2, header.width);
                    this.noiseBuffer[c][y][x] += noiseBuffer[c][cy][cx] * laplacian[iy][ix];
                }
            }
        });
        tasks.collect();
    }

    public void synthesizeNoise() {
        if (lfGlobal.noiseParameters == null)
            return;
        float[] lut = lfGlobal.noiseParameters.lut;
        // header.width here to avoid upsampling
        FlowHelper.parallelIterate(new IntPoint(header.width, header.height), (x, y) -> {
            // SPEC: spec doesn't mention the *0.5 here, it says *6
            // SPEC: spec doesn't mention clamping to 0 here
            float inScaledR = 3f * Math.max(0f, buffer[1][y][x] + buffer[0][y][x]);
            float inScaledG = 3f * Math.max(0f, buffer[1][y][x] - buffer[0][y][x]);
            int intInR;
            float fracInR;
            // LIBJXL: libjxl bug makes this >= 6D and 5, making lut[7] unused
            // SPEC: spec bug makes this >= 8D and 7, making lut[8] overflow
            if (inScaledR >= 7f) {
                intInR = 6;
                fracInR = 1f;
            } else {
                intInR = (int)inScaledR;
                fracInR = inScaledR - intInR;
            }
            int intInG;
            float fracInG;
            if (inScaledG >= 7f) {
                intInG = 6;
                fracInG = 1f;
            } else {
                intInG = (int)inScaledG;
                fracInG = inScaledG - intInG;
            }
            float sr = (lut[intInR + 1] - lut[intInR]) * fracInR + lut[intInR];
            float sg = (lut[intInG + 1] - lut[intInG]) * fracInG + lut[intInG];
            float nr = 0.22f * sr * (0.0078125f * noiseBuffer[0][y][x] + 0.9921875f * noiseBuffer[2][y][x]);
            float ng = 0.22f * sg * (0.0078125f * noiseBuffer[1][y][x] + 0.9921875f * noiseBuffer[2][y][x]);
            float nrg = nr + ng;
            buffer[0][y][x] += lfGlobal.lfChanCorr.baseCorrelationX * nrg + nr - ng;
            buffer[1][y][x] += nrg;
            buffer[2][y][x] += lfGlobal.lfChanCorr.baseCorrelationB * nrg;
        });
    }

    public LFGlobal getLFGlobal() {
        return lfGlobal;
    }

    public HFGlobal getHFGlobal() {
        return hfGlobal;
    }

    public HFPass getHFPass(int index) {
        return passes[index].hfPass;
    }

    public LFGroup getLFGroupForGroup(int group) {
        return lfGroups[groupXY(group).shiftRight(3).unwrapCoord(lfGroupRowStride)];
    }

    public int getNumLFGroups() {
        return numLFGroups;
    }

    public int getNumGroups() {
        return numGroups;
    }

    public int getGroupRowStride() {
        return groupRowStride;
    }

    public int getLFGroupRowStride() {
        return lfGroupRowStride;
    }

    public float[][][] getBuffer() {
        return buffer;
    }

    public boolean isDecoded() {
        return this.decoded;
    }

    public IntPoint groupXY(int group) {
        return IntPoint.coordinates(group, groupRowStride);
    }

    public IntPoint getLFGroupXY(int lfGroup) {
        return IntPoint.coordinates(lfGroup, lfGroupRowStride);
    }

    /*
     * @return The position of this group within this LF Group, 1-incrementing
     */
    public IntPoint groupPosInLFGroup(int lfGroupID, int groupID) {
        return groupXY(groupID).minus(getLFGroupXY(lfGroupID).shiftLeft(3));
    }

    public IntPoint groupSize(int group) {
        IntPoint groupxy = groupXY(group);
        IntPoint paddedSize = getPaddedFrameSize();
        return new IntPoint(Math.min(header.groupDim, paddedSize.x - groupxy.x * header.groupDim),
            Math.min(header.groupDim, paddedSize.y - groupxy.y * header.groupDim));
    }

    public IntPoint getLFGroupSize(int lfGroup) {
        IntPoint lfGroupXY = getLFGroupXY(lfGroup);
        IntPoint paddedSize = getPaddedFrameSize();
        return new IntPoint(Math.min(header.lfGroupDim, paddedSize.x - lfGroupXY.x * header.lfGroupDim),
            Math.min(header.lfGroupDim, paddedSize.y - lfGroupXY.y * header.lfGroupDim));
    }

    public IntPoint getPaddedFrameSize() {
        if (header.encoding == FrameFlags.VARDCT)
            return new IntPoint(width, height).ceilDiv(8).times(8);
        else
            return new IntPoint(width, height);
    }

    public IntPoint getModularFrameSize() {
        return new IntPoint(width, height);
    }

    public MATree getGlobalTree() {
        return globalTree;
    }

    public void setGlobalTree(MATree tree) {
        this.globalTree = tree;
    }

    public int getGroupBlockDim() {
        return header.groupDim >> 3;
    }

    public boolean isVisible() {
        return (header.type == FrameFlags.REGULAR_FRAME || header.type == FrameFlags.SKIP_PROGRESSIVE)
            && (header.duration != 0 || header.isLast);
    }

    /* gets the sample for the IMAGE position x and y */
    public float getSample(int c, int x, int y) {
        return x < header.origin.x || y < header.origin.y
            || x - header.origin.x >= width
            || y - header.origin.y >= height
            ? 0 : buffer[c][y - header.origin.y][x - header.origin.x];
    }

    public void printDebugInfo(Options options, PrintWriter err) {
        err.println("Frame Info:");
        err.format("    Encoding: %s%n", header.encoding == FrameFlags.VARDCT ? "VarDCT" : "Modular");
        String type = header.type == FrameFlags.REGULAR_FRAME ? "Regular"
                    : header.type == FrameFlags.LF_FRAME ? "LF Frame"
                    : header.type == FrameFlags.REFERENCE_ONLY ? "Reference Only"
                    : header.type == FrameFlags.SKIP_PROGRESSIVE ? "Skip Progressive"
                    : "????";
        err.format("    Type: %s%n", type);
        err.format("    Size: %dx%d%n", width, height);
        err.format("    Origin: (%d, %d)%n", header.origin.x, header.origin.y);
        err.format("    YCbCr: %b%n", header.doYCbCr);
    }
}
