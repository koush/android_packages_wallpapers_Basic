/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wallpaper.galaxy;

import android.renderscript.ScriptC;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.ProgramRaster;
import android.renderscript.Allocation;
import android.renderscript.Sampler;
import android.renderscript.Element;
import android.renderscript.SimpleMesh;
import android.renderscript.Primitive;
import android.renderscript.Type;
import static android.renderscript.Sampler.Value.LINEAR;
import static android.renderscript.Sampler.Value.NEAREST;
import static android.renderscript.Sampler.Value.WRAP;
import static android.renderscript.ProgramStore.DepthFunc.*;
import static android.renderscript.ProgramStore.BlendDstFunc;
import static android.renderscript.ProgramStore.BlendSrcFunc;
import static android.renderscript.ProgramFragment.EnvMode.*;
import static android.renderscript.Element.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.util.TimeZone;

import com.android.wallpaper.R;
import com.android.wallpaper.RenderScriptScene;

class GalaxyRS extends RenderScriptScene {
    private static final int GALAXY_RADIUS = 300;
    private static final int PARTICLES_COUNT = 12000;

    private static final int RSID_STATE = 0;
    private static final int RSID_PARTICLES = 1;
    private static final int RSID_PARTICLES_BUFFER = 2;

    private static final int TEXTURES_COUNT = 3;
    private static final int RSID_TEXTURE_SPACE = 0;
    private static final int RSID_TEXTURE_LIGHT1 = 1;
    private static final int RSID_TEXTURE_FLARES = 2;

    private final BitmapFactory.Options mOptionsARGB = new BitmapFactory.Options();

    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramFragment mPfBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramFragment mPfStars;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramStore mPfsBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramStore mPfsLights;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex mPvBackground;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex mPvStars;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Sampler mSampler;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Sampler mStarSampler;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex.MatrixAllocation mPvOrthoAlloc;
    @SuppressWarnings({"FieldCanBeLocal"})
    private ProgramVertex.MatrixAllocation mPvProjectionAlloc;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation[] mTextures;

    private GalaxyState mGalaxyState;
    private Type mStateType;
    private Allocation mState;
    private Allocation mParticles;
    private Type mParticlesType;
    private Allocation mParticlesBuffer;
    @SuppressWarnings({"FieldCanBeLocal"})
    private SimpleMesh mParticlesMesh;
    private ScriptC.Invokable mInitParticles;

    GalaxyRS(int width, int height) {
        super(width, height);

        mOptionsARGB.inScaled = false;
        mOptionsARGB.inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    @Override
    protected ScriptC createScript() {
        createProgramVertex();
        createProgramRaster();
        createProgramFragmentStore();
        createProgramFragment();
        createScriptStructures();
        loadTextures();

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setType(mStateType, "State", RSID_STATE);
        sb.setType(mParticlesMesh.getVertexType(0), "Particles", RSID_PARTICLES_BUFFER);
        sb.setType(mParticlesType, "Stars", RSID_PARTICLES);
        mInitParticles = sb.addInvokable("initParticles");
        sb.setScript(mResources, R.raw.galaxy);
        sb.setRoot(true);

        ScriptC script = sb.create();
        script.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        script.setTimeZone(TimeZone.getDefault().getID());

        script.bindAllocation(mState, RSID_STATE);
        script.bindAllocation(mParticles, RSID_PARTICLES);
        script.bindAllocation(mParticlesBuffer, RSID_PARTICLES_BUFFER);
        mInitParticles.execute();

        return script;
    }

    private void createScriptStructures() {
        createState();
        createParticlesMesh();
        createParticles();
    }

    private void createParticlesMesh() {
        final Builder elementBuilder = new Builder(mRS);
        elementBuilder.addUNorm8RGBA("");
        elementBuilder.addFloatXYZ("");
        elementBuilder.addFloatPointSize("");
        final Element vertexElement = elementBuilder.create();

        final SimpleMesh.Builder meshBuilder = new SimpleMesh.Builder(mRS);
        final int vertexSlot = meshBuilder.addVertexType(vertexElement, PARTICLES_COUNT);
        meshBuilder.setPrimitive(Primitive.POINT);
        mParticlesMesh = meshBuilder.create();
        mParticlesMesh.setName("ParticlesMesh");

        mParticlesBuffer = mParticlesMesh.createVertexAllocation(vertexSlot);
        mParticlesBuffer.setName("ParticlesBuffer");
        mParticlesMesh.bindVertexAllocation(mParticlesBuffer, 0);
    }

    @Override
    public void setOffset(float xOffset, float yOffset, int xPixels, int yPixels) {
        mGalaxyState.xOffset = xOffset;
        mState.data(mGalaxyState);
    }

    @Override
    public void resize(int width, int height) {
        super.resize(width, height);

        mGalaxyState.width = width;
        mGalaxyState.height = height;
        mGalaxyState.scale = width > height ? 1 : 0;
        mState.data(mGalaxyState);

        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);
        mInitParticles.execute();
    }

    static class GalaxyState {
        public int width;
        public int height;
        public int particlesCount;
        public int galaxyRadius;
        public float xOffset;
        public int isPreview;
        public int scale;
    }

    static class GalaxyParticle {
        public float angle;
        public float distance;
        public float speed;
        public float s;
        public float t;
    }

    private void createState() {
        boolean isPreview = isPreview();

        mGalaxyState = new GalaxyState();
        mGalaxyState.width = mWidth;
        mGalaxyState.height = mHeight;
        mGalaxyState.scale = mWidth > mHeight ? 1 : 0;
        mGalaxyState.particlesCount = PARTICLES_COUNT;
        mGalaxyState.galaxyRadius = GALAXY_RADIUS;
        mGalaxyState.isPreview = isPreview ? 1 : 0;
        if (isPreview) {
            mGalaxyState.xOffset = 0.5f;
        }

        mStateType = Type.createFromClass(mRS, GalaxyState.class, 1, "GalaxyState");
        mState = Allocation.createTyped(mRS, mStateType);
        mState.data(mGalaxyState);
    }

    private void createParticles() {
        mParticlesType = Type.createFromClass(mRS, GalaxyParticle.class, PARTICLES_COUNT, "Particle");
        mParticles = Allocation.createTyped(mRS, mParticlesType);
    }

    private void loadTextures() {
        mTextures = new Allocation[TEXTURES_COUNT];

        final Allocation[] textures = mTextures;
        textures[RSID_TEXTURE_SPACE] = loadTexture(R.drawable.space, "TSpace");
        textures[RSID_TEXTURE_LIGHT1] = loadTexture(R.drawable.light1, "TLight1");
        textures[RSID_TEXTURE_FLARES] = loadTextureARGB(R.drawable.flares, "TFlares");

        final int count = textures.length;
        for (int i = 0; i < count; i++) {
            textures[i].uploadToTexture(0);
        }
    }

    private Allocation loadTexture(int id, String name) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mResources,
                id, RGB_565(mRS), false);
        allocation.setName(name);
        return allocation;
    }

    // TODO: Fix Allocation.createFromBitmapResource() to do this when RGBA_8888 is specified
    private Allocation loadTextureARGB(int id, String name) {
        Bitmap b = BitmapFactory.decodeResource(mResources, id, mOptionsARGB);
        final Allocation allocation = Allocation.createFromBitmap(mRS, b, RGBA_8888(mRS), false);
        allocation.setName(name);
        return allocation;
    }

    private void createProgramFragment() {
        Sampler.Builder samplerBuilder = new Sampler.Builder(mRS);
        samplerBuilder.setMin(NEAREST);
        samplerBuilder.setMag(NEAREST);
        samplerBuilder.setWrapS(WRAP);
        samplerBuilder.setWrapT(WRAP);
        mSampler = samplerBuilder.create();

        ProgramFragment.Builder builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(REPLACE, 0);
        mPfBackground = builder.create();
        mPfBackground.setName("PFBackground");
        mPfBackground.bindSampler(mSampler, 0);

        samplerBuilder = new Sampler.Builder(mRS);
        samplerBuilder.setMin(LINEAR);
        samplerBuilder.setMag(LINEAR);
        samplerBuilder.setWrapS(WRAP);
        samplerBuilder.setWrapT(WRAP);
        mStarSampler = samplerBuilder.create();

        builder = new ProgramFragment.Builder(mRS, null, null);
        builder.setPointSpriteTexCoordinateReplacement(true);
        builder.setTexEnable(true, 0);
        builder.setTexEnvMode(MODULATE, 0);
        mPfStars = builder.create();
        mPfStars.setName("PFStars");
        mPfBackground.bindSampler(mStarSampler, 0);
    }

    private void createProgramFragmentStore() {
        ProgramStore.Builder builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.ONE, BlendDstFunc.ZERO);
        builder.setDitherEnable(false);
        builder.setDepthMask(false);
        mPfsBackground = builder.create();
        mPfsBackground.setName("PFSBackground");

        builder = new ProgramStore.Builder(mRS, null, null);
        builder.setDepthFunc(ALWAYS);
        builder.setBlendFunc(BlendSrcFunc.SRC_ALPHA, BlendDstFunc.ONE);
        builder.setDitherEnable(false);
        mPfsLights = builder.create();
        mPfsLights.setName("PFSLights");
    }

    private void createProgramVertex() {
        mPvOrthoAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvOrthoAlloc.setupOrthoWindow(mWidth, mHeight);

        ProgramVertex.Builder builder = new ProgramVertex.Builder(mRS, null, null);
        mPvBackground = builder.create();
        mPvBackground.bindAllocation(mPvOrthoAlloc);
        mPvBackground.setName("PVBackground");

        mPvProjectionAlloc = new ProgramVertex.MatrixAllocation(mRS);
        mPvProjectionAlloc.setupProjectionNormalized(mWidth, mHeight);

        builder = new ProgramVertex.Builder(mRS, null, null);
        mPvStars = builder.create();
        mPvStars.bindAllocation(mPvProjectionAlloc);
        mPvStars.setName("PVStars");
    }

    private void createProgramRaster() {
        ProgramRaster.Builder b = new ProgramRaster.Builder(mRS, null, null);
        b.setPointSmoothEnable(true);
        b.setPointSpriteEnable(true);
        mRS.contextBindProgramRaster(b.create());
    }

}
