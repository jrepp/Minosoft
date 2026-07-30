/* RENDERTARGETS: 0 */

flat in uint finTextureArray;
in float finTextureLayer;
in mediump vec2 finTextureUV;
uniform ivec2 gtextureSize;
// minosoft:texture_array_index finTextureArray

out vec4 foutColor;

#include "minosoft:texture"

void main() {
    if (gtextureSize.x <= 0 || gtextureSize.y <= 0) discard;
    vec4 texel = getTexture(finTextureArray, vec3(finTextureUV, finTextureLayer));
    if (texel.a < 0.1) discard;
    foutColor = vec4(vec3(gl_FragCoord.z), 1.0);
}
