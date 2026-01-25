//Common utility functions for decoding and operating on quads

vec3 swizzelDataAxis(uint axis, vec3 data) {
    return mix(mix(data.zxy,data.xzy,bvec3(axis==0)),data,bvec3(axis==1));
}

uint extractDetail(uvec2 encPos) {
    return encPos.x>>28;
}

ivec3 extractLoDPosition(uvec2 encPos) {
    int y = ((int(encPos.x)<<4)>>24);
    int x = (int(encPos.y)<<4)>>8;
    int z = int((encPos.x&((1u<<20)-1))<<4);
    z |= int(encPos.y>>28);
    z <<= 8;
    z >>= 8;
    return ivec3(x,y,z);
}

vec4 getFaceSize(uint faceData) {
    float EPSILON = 0.00005f;

    vec4 faceOffsetsSizes = extractFaceSizes(faceData);

    //Expand the quads by a very small amount (because of the subtraction after this also becomes an implicit add)
    faceOffsetsSizes.xz -= vec2(EPSILON);

    //Make the end relative to the start
    faceOffsetsSizes.yw -= faceOffsetsSizes.xz;

    return faceOffsetsSizes;
}


vec2 taaOffset = vec2(0);//TODO: compute this

struct QuadData {
    uvec4 attributeData;

    float lodScale;
    uint axis;
    //Used for computing the 4 corners of the quad
    vec3 basePoint;
    vec2 quadSizeAddin;
    vec2 uvCorner;
};

uint makeQuadFlags(uint faceData, uint modelId, ivec2 quadSize, const in BlockModel model, uint face) {
    //bit: 0-use cuttout, 1-dont use mipmaps, 2|3-tint state, 4|6-face, 8|11-width, 12|15-height, 16|31-model id
    uint flags = 0;

    flags |= modelId<<16;//Model id
    flags |= (uint(quadSize.x-1)<<8)|(uint(quadSize.y-1)<<12);//quad size

    {//Cuttout
        flags |= faceHasAlphaCuttout(faceData);
        flags |= uint(any(greaterThan(quadSize, ivec2(1)))) & faceHasAlphaCuttoutOverride(faceData);
    }

    flags |= uint(!modelHasMipmaps(model))<<1;//Not mipmaps

    flags |= faceTintState(faceData)<<2;
    flags |= face<<4;//Face

    return flags;
}

uint packVec4(vec4 vec) {
    uvec4 vec_=uvec4(vec*255)<<uvec4(24,16,8,0);
    return vec_.x|vec_.y|vec_.z|vec_.w;
}

float faceShadeFactor(uint face, bool isShaded) {
    uint axis = face>>1u;
    float axisIs1 = float(int(axis==1u));
    float axisIs2 = float(int(axis==2u));
    float axisIs0 = 1.0f - axisIs1 - axisIs2;

    #ifdef DARKENED_TINTING
    float shaded = axisIs1*0.8f + axisIs2*0.6f + axisIs0*0.9f;
    float unshaded = 0.9f;
    return mix(unshaded, shaded, float(int(isShaded)));
    #else
    float shaded = axisIs1*0.8f + axisIs2*0.6f + axisIs0*1.0f;
    float downFactor = mix(1.0f, 0.5f, float(int(face==0u)));
    float shade = shaded * downFactor;
    return mix(1.0f, shade, float(int(isShaded)));
    #endif
}

uvec3 makeRemainingAttributes(const in BlockModel model, const in Quad quad, uint lodLevel, uint face) {
    uvec3 attributes = uvec3(0);

    uint lighting = extractLightId(quad);

    //Apply model colour tinting
    uint tintColour = model.colourTint;

    if (modelHasBiomeLUT(model)) {
        tintColour = colourData[tintColour + extractBiomeId(quad)];
    }

    #ifdef PATCHED_SHADER
    attributes.x = lighting;
    attributes.y = tintColour;
    #else
    bool isTranslucent = modelIsTranslucent(model);
    bool hasAO = modelHasMipmaps(model);//TODO: replace with per face AO flag
    bool isShaded = hasAO;//TODO: make this a per face flag

    vec4 tinting = getLighting(lighting);

    uint conditionalTinting = 0;
    if (tintColour != uint(-1)) {
        conditionalTinting = tintColour;
    }

    uint addin = 0;
    if (!isTranslucent) {
        tinting.w = 0.0;
        //Encode the face, the lod level and
        uint encodedData = 0;
        encodedData |= face;
        encodedData |= (lodLevel<<3);
        encodedData |= uint(hasAO)<<6;
        addin = encodedData;
    }

    //Apply face tint
    tinting.xyz *= faceShadeFactor(face, isShaded);
    attributes.x = packVec4(tinting);
    attributes.y = conditionalTinting;
    attributes.z = addin|(face<<8);
    #endif

    return attributes;
}

void setupQuad(out QuadData quad, const in Quad rawQuad, uvec2 sPos, bool generateAttributes) {
    uint lodLevel = extractDetail(sPos);
    float lodScale = 1<<lodLevel;
    ivec3 baseSection = (extractLoDPosition(sPos)<<lodLevel) - baseSectionPos;

    uint face = extractFace(rawQuad);
    uint modelId = extractStateId(rawQuad);
    BlockModel model = modelData[modelId];
    uint faceData = model.faceData[face];
    ivec2 quadSize = extractSize(rawQuad);

    if (generateAttributes) {
        quad.attributeData.x = makeQuadFlags(faceData, modelId, quadSize, model, face);
        quad.attributeData.yzw = makeRemainingAttributes(model, rawQuad, lodLevel, face);
    }

    vec4 faceSize = getFaceSize(faceData);

    vec3 quadStart = extractPos(rawQuad);
    float depthOffset = extractFaceIndentation(faceData);
    quadStart += swizzelDataAxis(face>>1, vec3(faceSize.xz, mix(depthOffset, 1-depthOffset, float(face&1u))));

    quad.lodScale = lodScale;
    quad.axis = face>>1;
    quad.basePoint = (quadStart*lodScale)+vec3(baseSection<<5);
    quad.quadSizeAddin = (faceSize.yw + quadSize - 1);
    quad.uvCorner = faceSize.xz;
}

vec4 getQuadCornerPos(in QuadData quad, uint cornerId) {
    vec2 cornerMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;
    vec3 point = quad.basePoint + swizzelDataAxis(quad.axis,vec3(quad.quadSizeAddin*cornerMask,0));
    vec4 pos = MVP * vec4(point, 1.0f);
    pos.xy += taaOffset*pos.w;
    return pos;
}

#ifdef USE_INTERPOLATED_UV
vec2 getCornerUV(const in QuadData quad, uint cornerId) {
    return quad.uvCorner + quad.quadSizeAddin*vec2((cornerId>>1)&1u, cornerId&1u);
}
#endif
