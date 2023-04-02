package autoprob.go.vis.widget;

import java.awt.image.RGBImageFilter;


// the GhostFilter makes an outline of a stone for hovering -- just draws every other pixel
public class GhostFilter extends RGBImageFilter {
    public GhostFilter() {}
    public int filterRGB(int x, int y, int rgb) {
        if (((x + y) & 1) == 1)
            rgb &= 0x00ffffff;
        return rgb;
    }
}