layout (location = 0) in vec3 vinPosition;
layout (location = 1) in float vinAmbientUV;
layout (location = 2) in float vinTexture;
layout (location = 3) in float vinLightTint;
layout (location = 4) in vec2 mc_Entity;
layout (location = 5) in vec2 at_midTexCoord;
layout (location = 6) in vec4 at_tangent;
layout (location = 7) in vec3 vaNormal;
layout (location = 8) in vec4 at_midBlock;
uniform ivec2 atlasSize;
// minosoft:texture_array_index terrainTextureArray

#ifndef MINOSOFT_SHADOW_MATRICES
#define MINOSOFT_SHADOW_MATRICES
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
#endif

#include "minosoft:vsh"
#include "minosoft:animation"

void main() {
    uint terrainTextureArray = floatBitsToUint(vinTexture) >> 28u;
    float materialGuard =
        dot(mc_Entity, vec2(1.0)) +
        dot(at_midTexCoord, vec2(1.0)) +
        dot(at_tangent, vec4(1.0)) +
        dot(vaNormal, vec3(1.0)) +
        dot(at_midBlock, vec4(1.0)) +
        float(atlasSize.x + atlasSize.y);
    gl_Position = renderStage < 0
        ? vec4(0.0)
        : shadowProjection * shadowModelView * vec4(vinPosition, 1.0);
    if (isnan(materialGuard)) gl_Position = vec4(0.0);
    setTexture(uv_unpack(floatBitsToUint(vinAmbientUV)), vinTexture);
}
