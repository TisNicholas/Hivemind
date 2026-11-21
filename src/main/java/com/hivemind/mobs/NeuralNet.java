package com.hivemind.mobs;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Small fully-connected network (leaky-ReLU hidden layers, linear output)
 * trained online with SGD on a Huber-style loss. No external libraries.
 */
public class NeuralNet {
    private final int[] sizes;
    private final int layers;
    private final float[][][] w; // w[layer][out][in]
    private final float[][] b;

    public NeuralNet(int[] sizes, Random rnd) {
        this.sizes = sizes.clone();
        this.layers = sizes.length - 1;
        this.w = new float[layers][][];
        this.b = new float[layers][];
        for (int l = 0; l < layers; l++) {
            w[l] = new float[sizes[l + 1]][sizes[l]];
            b[l] = new float[sizes[l + 1]];
            float scale = (float) Math.sqrt(2.0 / sizes[l]);
            if (l == layers - 1) scale *= 0.1f;
            for (int j = 0; j < sizes[l + 1]; j++)
                for (int i = 0; i < sizes[l]; i++)
                    w[l][j][i] = (float) rnd.nextGaussian() * scale;
        }
    }

    private float[][] forward(float[] x) {
        float[][] a = new float[layers + 1][];
        a[0] = x;
        for (int l = 0; l < layers; l++) {
            float[] in = a[l];
            float[] out = new float[sizes[l + 1]];
            for (int j = 0; j < out.length; j++) {
                float s = b[l][j];
                float[] row = w[l][j];
                for (int i = 0; i < in.length; i++) s += row[i] * in[i];
                if (l < layers - 1 && s < 0) s *= 0.01f;
                out[j] = s;
            }
            a[l + 1] = out;
        }
        return a;
    }

    public float[] predict(float[] x) {
        return forward(x)[layers];
    }

    /**
     * One gradient step pulling Q(x)[action] toward target.
     * dInput (optional) receives the gradient w.r.t. the input vector,
     * which is how the learned per-mob-type embeddings get trained.
     */
    public void trainSample(float[] x, int action, float target, float lr, float[] dInput) {
        float[][] a = forward(x);
        float err = a[layers][action] - target;
        float g = Math.max(-1f, Math.min(1f, err)); // Huber gradient

        float[] delta = new float[sizes[layers]];
        delta[action] = g;

        for (int l = layers - 1; l >= 0; l--) {
            float[] prev = new float[sizes[l]];
            for (int j = 0; j < sizes[l + 1]; j++) {
                float d = delta[j];
                if (d == 0f) continue;
                float[] row = w[l][j];
                for (int i = 0; i < sizes[l]; i++) prev[i] += row[i] * d;
            }
            for (int j = 0; j < sizes[l + 1]; j++) {
                float d = delta[j];
                if (d == 0f) continue;
                float[] row = w[l][j];
                float step = lr * d;
                for (int i = 0; i < sizes[l]; i++) row[i] -= step * a[l][i];
                b[l][j] -= step;
            }
            if (l > 0) {
                for (int i = 0; i < prev.length; i++) if (a[l][i] < 0) prev[i] *= 0.01f;
            }
            delta = prev;
        }
        if (dInput != null) System.arraycopy(delta, 0, dInput, 0, Math.min(dInput.length, delta.length));
    }

    public void copyFrom(NeuralNet o) {
        for (int l = 0; l < layers; l++) {
            for (int j = 0; j < sizes[l + 1]; j++) System.arraycopy(o.w[l][j], 0, w[l][j], 0, sizes[l]);
            System.arraycopy(o.b[l], 0, b[l], 0, sizes[l + 1]);
        }
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(layers);
        for (int s : sizes) out.writeInt(s);
        for (int l = 0; l < layers; l++) {
            for (int j = 0; j < sizes[l + 1]; j++) {
                for (int i = 0; i < sizes[l]; i++) out.writeFloat(w[l][j][i]);
                out.writeFloat(b[l][j]);
            }
        }
    }

    /** Returns false (and leaves the net untouched) if the saved shape doesn't match. */
    public boolean read(DataInputStream in) throws IOException {
        if (in.readInt() != layers) return false;
        for (int s : sizes) if (in.readInt() != s) return false;
        for (int l = 0; l < layers; l++) {
            for (int j = 0; j < sizes[l + 1]; j++) {
                for (int i = 0; i < sizes[l]; i++) w[l][j][i] = in.readFloat();
                b[l][j] = in.readFloat();
            }
        }
        return true;
    }
}
