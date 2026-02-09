package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.AutoBindingShader;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import static me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil.PRINTF_processor;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_UNPACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_UNPACK_SKIP_IMAGES;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.*;

// TODO: swap to persistent gpu threads instead of dispatching MAX_ITERATIONS of compute layers
public class HierarchicalOcclusionTraverser {
    public static final boolean HIERARCHICAL_SHADER_DEBUG = System.getProperty("voxy.hierarchicalShaderDebug", "false").equals("true");

    public static final int MAX_REQUEST_QUEUE_SIZE = 50;
    public static final int MAX_QUEUE_SIZE = 200_000;


    private static final int MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER+1;
    private static final int LOCAL_WORK_SIZE_BITS = 5;

    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final RenderGenerationService meshGen;

    private int warmupFrameCount = 0;
    private static final int WARMUP_FRAMES = 120; 
    private static final float WARMUP_SUBDIVISION_MULTIPLIER = 0.25f; 

    //计算优先级
    private volatile Viewport<?> cachedViewport;
    private volatile float cachedMinSSS;

    private final GlBuffer requestBuffer;

    private final GlBuffer nodeBuffer;
    private final GlBuffer uniformBuffer = new GlBuffer(1024).zero();
    private final GlBuffer statisticsBuffer = new GlBuffer(1024).zero();


    private int topNodeCount;
    private final Int2IntOpenHashMap topNode2idxMapping = new Int2IntOpenHashMap();//Used to store mapping from TLN to array index
    private final int[] idx2topNodeMapping = new int[MAX_QUEUE_SIZE];//Used to map idx to TLN id
    private final GlBuffer topNodeIds = new GlBuffer(MAX_QUEUE_SIZE*4).zero();
    private final GlBuffer queueMetaBuffer = new GlBuffer(4*4*MAX_ITERATIONS).zero();
    private final GlBuffer scratchQueueA = new GlBuffer(MAX_QUEUE_SIZE*4).zero();
    private final GlBuffer scratchQueueB = new GlBuffer(MAX_QUEUE_SIZE*4).zero();

    private static int BINDING_COUNTER = 1;
    private static final int SCENE_UNIFORM_BINDING = BINDING_COUNTER++;
    private static final int REQUEST_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int RENDER_QUEUE_BINDING = BINDING_COUNTER++;
    private static final int NODE_DATA_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_INDEX_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_META_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SOURCE_BINDING = BINDING_COUNTER++;
    private static final int NODE_QUEUE_SINK_BINDING = BINDING_COUNTER++;
    private static final int RENDER_TRACKER_BINDING = BINDING_COUNTER++;
    private static final int STATISTICS_BUFFER_BINDING = BINDING_COUNTER++;

    private final int hizSampler = glGenSamplers();

    private final AutoBindingShader traversal = Shader.makeAuto(PRINTF_processor)
            .defineIf("DEBUG", HIERARCHICAL_SHADER_DEBUG)
            .define("MAX_ITERATIONS", MAX_ITERATIONS)
            .define("LOCAL_SIZE_BITS", LOCAL_WORK_SIZE_BITS)
            .define("MAX_REQUEST_QUEUE_SIZE", MAX_REQUEST_QUEUE_SIZE)

            .define("HIZ_BINDING", 0)

            .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM_BINDING)
            .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE_BINDING)
            .define("RENDER_QUEUE_BINDING", RENDER_QUEUE_BINDING)
            .define("NODE_DATA_BINDING", NODE_DATA_BINDING)

            .define("NODE_QUEUE_INDEX_BINDING", NODE_QUEUE_INDEX_BINDING)
            .define("NODE_QUEUE_META_BINDING", NODE_QUEUE_META_BINDING)
            .define("NODE_QUEUE_SOURCE_BINDING", NODE_QUEUE_SOURCE_BINDING)
            .define("NODE_QUEUE_SINK_BINDING", NODE_QUEUE_SINK_BINDING)

            .define("RENDER_TRACKER_BINDING", RENDER_TRACKER_BINDING)

            .defineIf("HAS_STATISTICS", RenderStatistics.enabled)
            .defineIf("STATISTICS_BUFFER_BINDING", RenderStatistics.enabled, STATISTICS_BUFFER_BINDING)

            .add(ShaderType.COMPUTE, "voxy:lod/hierarchical/traversal_dev.comp")
            .compile();


    public HierarchicalOcclusionTraverser(AsyncNodeManager nodeManager, NodeCleaner nodeCleaner, RenderGenerationService meshGen) {
        this.nodeCleaner = nodeCleaner;
        this.nodeManager = nodeManager;
        this.meshGen = meshGen;
        this.requestBuffer = new GlBuffer(MAX_REQUEST_QUEUE_SIZE*8L+8).zero();
        this.nodeBuffer = new GlBuffer(nodeManager.maxNodeCount*16L).fill(-1);


        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(this.hizSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);

        this.traversal
                .ubo("SCENE_UNIFORM_BINDING", this.uniformBuffer)
                .ssbo("REQUEST_QUEUE_BINDING", this.requestBuffer)
                .ssbo("NODE_DATA_BINDING", this.nodeBuffer)
                .ssbo("NODE_QUEUE_META_BINDING", this.queueMetaBuffer)
                .ssbo("RENDER_TRACKER_BINDING", this.nodeCleaner.visibilityBuffer)
                .ssboIf("STATISTICS_BUFFER_BINDING", this.statisticsBuffer);

        this.topNode2idxMapping.defaultReturnValue(-1);
        this.nodeManager.setTLNAddRemoveCallbacks(this::addTLN, this::remTLN);
    }

    private void addTLN(int id) {
        int aid = this.topNodeCount++;//Increment buffer
        if (this.topNodeCount > this.topNodeIds.size()/4) {
            throw new IllegalStateException("Top level node count greater than capacity");
        }

        //Use clear buffer, yes know is a bad idea, TODO: replace
        //Add the new top level node to the queue
        MemoryUtil.memPutInt(SCRATCH, id);
        nglClearNamedBufferSubData(this.topNodeIds.id, GL_R32UI, aid * 4L, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, SCRATCH);

        if (this.topNode2idxMapping.put(id, aid) != -1) {
            throw new IllegalStateException();
        }
        this.idx2topNodeMapping[aid] = id;
    }

    private void remTLN(int id) {
        //Remove id
        int idx = this.topNode2idxMapping.remove(id);
        //Decrement count
        this.topNodeCount--;
        if (idx == -1) {
            throw new IllegalStateException();
        }

        //Count has already been decremented so is an exact match
        //If we are at the end of the array we dont need to do anything
        if (idx == this.topNodeCount) {
            return;
        }

        //Move the entry at the end to the current index
        int endTLNId = this.idx2topNodeMapping[this.topNodeCount];
        this.idx2topNodeMapping[idx] = endTLNId;//Set the old to the new
        if (this.topNode2idxMapping.put(endTLNId, idx) == -1)
            throw new IllegalStateException();

        //Move it server side, from end to new idx
        MemoryUtil.memPutInt(SCRATCH, endTLNId);
        nglClearNamedBufferSubData(this.topNodeIds.id, GL_R32UI, idx*4L, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, SCRATCH);
    }

    private static void setFrustum(Viewport<?> viewport, long ptr) {
        for (int i = 0; i < 6; i++) {
            var plane = viewport.frustumPlanes[i];
            plane.getToAddress(ptr); ptr += 4*4;
        }
    }

    private void uploadUniform(Viewport<?> viewport) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 1024);

        viewport.MVP.getToAddress(ptr); ptr += 4*4*4;

        viewport.section.getToAddress(ptr); ptr += 4*3;

        //MemoryUtil.memPutFloat(ptr, viewport.width); ptr += 4;
        MemoryUtil.memPutInt(ptr, viewport.hiZBuffer.getPackedLevels()); ptr += 4;

        viewport.innerTranslation.getToAddress(ptr); ptr += 4*3;

        //MemoryUtil.memPutFloat(ptr, viewport.height); ptr += 4;

        //动态调整阈值
        float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize * VoxyConfig.CONFIG.subDivisionSize;

        if (warmupFrameCount < WARMUP_FRAMES) {
            float warmupProgress = (float) warmupFrameCount / WARMUP_FRAMES; // 0.0 -> 1.0
            float multiplier = WARMUP_SUBDIVISION_MULTIPLIER + (1.0f - WARMUP_SUBDIVISION_MULTIPLIER) * warmupProgress;
            screenspaceAreaDecreasingSize *= multiplier;
            warmupFrameCount++;
        }

        //Screen space size for descending
        MemoryUtil.memPutFloat(ptr, (float) (screenspaceAreaDecreasingSize) /(viewport.width*viewport.height)); ptr += 4;

        setFrustum(viewport, ptr); ptr += 4*4*6;

        MemoryUtil.memPutInt(ptr, (int) (viewport.getRenderList().size()/4-1)); ptr += 4;

        //VisibilityId
        MemoryUtil.memPutInt(ptr, this.nodeCleaner.visibilityId); ptr += 4;

        {
            //改进的队列大小计算
            final double TARGET_COUNT = 4000;
            double currentTaskCount = this.meshGen.getTaskCount();
            int baseRequestSize = MAX_REQUEST_QUEUE_SIZE / 2; // 默认至少一半

            if (warmupFrameCount < WARMUP_FRAMES) {
                baseRequestSize = MAX_REQUEST_QUEUE_SIZE;
            } else {
                double iFillness = Math.max(0, (TARGET_COUNT - currentTaskCount) / TARGET_COUNT);
                iFillness = Math.sqrt(iFillness);

                int dynamicSize = (int) Math.ceil(iFillness * MAX_REQUEST_QUEUE_SIZE);
                baseRequestSize = Math.max(baseRequestSize, dynamicSize);
            }

            MemoryUtil.memPutInt(ptr, Math.max(0, Math.min(MAX_REQUEST_QUEUE_SIZE, baseRequestSize)));
            ptr += 4;
        }
    }

    private void bindings(Viewport<?> viewport) {
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, this.queueMetaBuffer.id);

        //Bind the hiz buffer
        glBindTextureUnit(0, viewport.hiZBuffer.getHizTextureId());
        glBindSampler(0, this.hizSampler);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, RENDER_QUEUE_BINDING, viewport.getRenderList().id);
    }

    public void doTraversal(Viewport<?> viewport) {
        this.uploadUniform(viewport);
        //UploadStream.INSTANCE.commit(); //Done inside traversal

        //缓存viewport用于后续计算
        this.cachedViewport = viewport;
        float screenspaceAreaDecreasingSize = VoxyConfig.CONFIG.subDivisionSize * VoxyConfig.CONFIG.subDivisionSize;
        if (warmupFrameCount < WARMUP_FRAMES) {
            float warmupProgress = (float) warmupFrameCount / WARMUP_FRAMES;
            float multiplier = WARMUP_SUBDIVISION_MULTIPLIER + (1.0f - WARMUP_SUBDIVISION_MULTIPLIER) * warmupProgress;
            screenspaceAreaDecreasingSize *= multiplier;
        }
        this.cachedMinSSS = screenspaceAreaDecreasingSize / (viewport.width * viewport.height);

        this.traversal.bind();
        this.bindings(viewport);
        PrintfDebugUtil.bind();

        if (RenderStatistics.enabled) {
            this.statisticsBuffer.zero();
        }

        //Clear the render output counter
        nglClearNamedBufferSubData(viewport.getRenderList().id, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);

        //Traverse
        this.traverseInternal();

        this.downloadResetRequestQueue();

        if (RenderStatistics.enabled) {
            DownloadStream.INSTANCE.download(this.statisticsBuffer, down->{
                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalTraversalCounts[i] = MemoryUtil.memGetInt(down.address+i*4L);
                }

                for (int i = 0; i < MAX_ITERATIONS; i++) {
                    RenderStatistics.hierarchicalRenderSections[i] = MemoryUtil.memGetInt(down.address+MAX_ITERATIONS*4L+i*4L);
                }
            });
        }

        //Bind the hiz buffer
        glBindSampler(0, 0);
        glBindTextureUnit(0, 0);
    }

    private void traverseInternal() {
        {
            //Fix mesa bug
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
        }

        int firstDispatchSize = (this.topNodeCount+(1<<LOCAL_WORK_SIZE_BITS)-1)>>LOCAL_WORK_SIZE_BITS;
        /*
        //prime the queue Todo: maybe move after the traversal? cause then it is more efficient work since it doesnt need to wait for this before starting?
        glClearNamedBufferData(this.queueMetaBuffer.id, GL_RGBA32UI, GL_RGBA, GL_UNSIGNED_INT, new int[]{0,1,1,0});//Prime the metadata buffer, which also contains

        //Set the first entry
        glClearNamedBufferSubData(this.queueMetaBuffer.id, GL_RGBA32UI, 0, 16, GL_RGBA, GL_UNSIGNED_INT, new int[]{firstDispatchSize,1,1,initialQueueSize});
         */
        {//TODO:FIXME: THIS IS BULLSHIT BY INTEL need to fix the clearing
            long ptr = UploadStream.INSTANCE.upload(this.queueMetaBuffer, 0, 16*MAX_ITERATIONS);
            MemoryUtil.memPutInt(ptr +  0, firstDispatchSize);
            MemoryUtil.memPutInt(ptr +  4, 1);
            MemoryUtil.memPutInt(ptr +  8, 1);
            MemoryUtil.memPutInt(ptr + 12, this.topNodeCount);
            for (int i = 1; i < MAX_ITERATIONS; i++) {
                MemoryUtil.memPutInt(ptr + (i*16)+ 0, 0);
                MemoryUtil.memPutInt(ptr + (i*16)+ 4, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+ 8, 1);
                MemoryUtil.memPutInt(ptr + (i*16)+12, 0);
            }
            UploadStream.INSTANCE.commit();
        }

        //Execute first iteration
        glUniform1ui(NODE_QUEUE_INDEX_BINDING, 0);

        //Use the top node id buffer
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, this.topNodeIds.id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, this.scratchQueueB.id);

        //Dont need to use indirect to dispatch the first iteration
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT|GL_BUFFER_UPDATE_BARRIER_BIT);
        glDispatchCompute(firstDispatchSize, 1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);

        //Dispatch max iterations
        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            glUniform1ui(NODE_QUEUE_INDEX_BINDING, iter);

            //Flipflop buffers
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SOURCE_BINDING, ((iter & 1) == 0 ? this.scratchQueueA : this.scratchQueueB).id);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, NODE_QUEUE_SINK_BINDING, ((iter & 1) == 0 ? this.scratchQueueB : this.scratchQueueA).id);

            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

            //Dispatch and barrier
            glDispatchComputeIndirect(iter * 4 * 4);
        }

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);
    }


    private void downloadResetRequestQueue() {
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
        nglClearNamedBufferSubData(this.requestBuffer.id, GL_R32UI, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, 0);
    }

    private void forwardDownloadResult(long ptr, long size) {
        int count = MemoryUtil.memGetInt(ptr);ptr += 8;//its 8 since we need to skip the second value (which is empty)
        if (count < 0 || count > 50000) {
            Logger.error(new IllegalStateException("Count unexpected extreme value: " + count + " things may get weird"));
            return;
        }
        if (count > (this.requestBuffer.size()>>3)-1) {
            //This should not break the synchonization between gpu and cpu as in the traversal shader is
            // `if (atomRes < REQUEST_QUEUE_SIZE) {` which forcefully clamps to the request size

            //Logger.warn("Count over max buffer size, clamping, got count: " + count + ".");

            count = (int) ((this.requestBuffer.size()>>3)-1);

            //Write back the clamped count
            MemoryUtil.memPutInt(ptr-8, count);
        }
        //if (count > REQUEST_QUEUE_SIZE) {
        //    Logger.warn("Count larger than 'maxRequestCount', overflow captured. Overflowed by " + (count-REQUEST_QUEUE_SIZE));
        //}
        if (count != 0) {
            //渐进式优先级细分
            if (VoxyConfig.CONFIG.enablePrioritySubdivision && count > 1 && this.cachedViewport != null) {
                sortRequestsByPriority(ptr, count);
            }
            
            this.nodeManager.submitRequestBatch(new MemoryBuffer(count*8L+8).cpyFrom(ptr-8));// the -8 is because we incremented it by 8
        }
    }

    private void sortRequestsByPriority(long ptr, int count) {
        Viewport<?> viewport = this.cachedViewport;
        if (viewport == null) return;
        double camSecX = viewport.section.x;
        double camSecY = viewport.section.y;
        double camSecZ = viewport.section.z;

        float[] priorities = new float[count];
        
        //计算每个请求优先级
        for (int i = 0; i < count; i++) {
            int posX = MemoryUtil.memGetInt(ptr + i * 8);
            int posY = MemoryUtil.memGetInt(ptr + i * 8 + 4);
            int lodLevel = (posX >>> 28) & 0xF;
            
            //解码坐标
            int y = (posX << 4) >> 24;
            int x = (posY << 4) >> 8;
            int z = ((posX & ((1 << 20) - 1)) << 4);
            z |= (posY >>> 28);
            z = (z << 8) >> 8;
            
            int nodeSize = 1 << lodLevel;
            double nodeCenterX = x * nodeSize + nodeSize * 0.5;
            double nodeCenterY = y * nodeSize + nodeSize * 0.5;
            double nodeCenterZ = z * nodeSize + nodeSize * 0.5;
            
            //计算与玩家位置
            double dx = nodeCenterX - camSecX;
            double dy = nodeCenterY - camSecY;
            double dz = nodeCenterZ - camSecZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            
            float distanceWeight = (float) (1.0 / (1.0 + distanceSq * 0.00001));
            
            float lodWeight = 2.0f - lodLevel * 0.3f;
            
            double screenSpaceEstimate = (nodeSize * nodeSize) / Math.max(1.0, distanceSq);
            float screenWeight = (float) Math.min(2.0, screenSpaceEstimate * 10000);
            
            priorities[i] = distanceWeight * lodWeight * screenWeight;
        }
        
        for (int i = 1; i < count; i++) {
            float currentPriority = priorities[i];
            int currentPosX = MemoryUtil.memGetInt(ptr + i * 8);
            int currentPosY = MemoryUtil.memGetInt(ptr + i * 8 + 4);
            
            int j = i - 1;
            while (j >= 0 && priorities[j] < currentPriority) {
                priorities[j + 1] = priorities[j];
                int moveX = MemoryUtil.memGetInt(ptr + j * 8);
                int moveY = MemoryUtil.memGetInt(ptr + j * 8 + 4);
                MemoryUtil.memPutInt(ptr + (j + 1) * 8, moveX);
                MemoryUtil.memPutInt(ptr + (j + 1) * 8 + 4, moveY);
                j--;
            }
            
            if (j + 1 != i) {
                priorities[j + 1] = currentPriority;
                MemoryUtil.memPutInt(ptr + (j + 1) * 8, currentPosX);
                MemoryUtil.memPutInt(ptr + (j + 1) * 8 + 4, currentPosY);
            }
        }
    }

    public GlBuffer getNodeBuffer() {
        return this.nodeBuffer;
    }


    public void resetWarmup() {
        this.warmupFrameCount = 0;
    }

    public void free() {
        this.traversal.free();
        this.requestBuffer.free();
        this.nodeBuffer.free();
        this.uniformBuffer.free();
        this.statisticsBuffer.free();
        this.queueMetaBuffer.free();
        this.topNodeIds.free();
        this.scratchQueueA.free();
        this.scratchQueueB.free();
        glDeleteSamplers(this.hizSampler);
    }

    private static final long SCRATCH = MemoryUtil.nmemAlloc(32);//32 bytes of scratch memory
}
