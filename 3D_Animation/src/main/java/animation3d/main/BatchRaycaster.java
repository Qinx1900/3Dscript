package animation3d.main;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import animation3d.renderer3d.ExtendedRenderingState;
import animation3d.renderer3d.OpenCLProgram;
import animation3d.renderer3d.OpenCLRaycaster;
import animation3d.renderer3d.Renderer3D;
import animation3d.renderer3d.RenderingAlgorithm;
import animation3d.renderer3d.Scalebar;
import animation3d.textanim.Animator;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;


public class BatchRaycaster implements PlugInFilter {

	private ImagePlus image;

	private Renderer3D renderer;

	public BatchRaycaster() {
	}

	@Override
	public int setup(String arg, ImagePlus imp) {
		this.image = imp;
		return DOES_8G | DOES_16;
	}

	private String loadText(String file) throws IOException {
		BufferedReader buf = new BufferedReader(new FileReader(file));
		String line = null;
		StringBuffer res = new StringBuffer();
		while((line = buf.readLine()) != null) {
			res.append(line).append("\n");
		}
		buf.close();
		return res.toString();
	}

	@Override
	public void run(ImageProcessor ip) {
//		calculateChannelMinAndMax();
		GenericDialogPlus gd = new GenericDialogPlus("");
		gd.addFileField("Animation file", "");
		gd.addNumericField("Output_width", image.getWidth(), 0);
		gd.addNumericField("Output_height", image.getHeight(), 0);

		String[] scalebarChoice = Scalebar.Position.getNames();
		gd.addChoice("Scalebar_position", scalebarChoice, Scalebar.Position.VIEW_LOWER_LEFT.name());
		gd.addNumericField("Scalebar length", 0, 0);
		gd.addNumericField("Scalebar_offset", 10, 2);
		gd.addCheckbox("Bounding_Box", true);

		String[] renderingChoice = new String[] {
				"Independent transparency",
				"Combined transparency",
				"Maximum intensity projection"};
		gd.addChoice("Rendering_algorithm", renderingChoice, renderingChoice[0]);

		gd.showDialog();
		if(gd.wasCanceled())
			return;

		String animationFile = gd.getNextString();
		int w = (int)gd.getNextNumber();
		int h = (int)gd.getNextNumber();
		Scalebar.Position scalebarPos = Scalebar.Position.values()[gd.getNextChoiceIndex()];
		int scalebar = (int)gd.getNextNumber();
		float scalebarOffs = (float)gd.getNextNumber();
		int algo = gd.getNextChoiceIndex();
		RenderingAlgorithm algorithm = RenderingAlgorithm.values()[algo];
		boolean bb = gd.getNextBoolean();

		String animation = null;
		try {
			animation = loadText(animationFile);
		} catch(Exception e) {
			IJ.handleException(e);
			return;
		}

		try {
			renderer = new Renderer3D(image, w, h);
		} catch(UnsatisfiedLinkError e) {
			IJ.handleException(e);
			IJ.error("Either your graphics card doesn't support OpenCL "
					+ "or your drivers are not uptodate. Please install "
					+ "the newest drivers for your card and try again.");
			return;
		}
//		ExtendedRenderingState rs = renderer.getRenderingState();

//		rs.setChannelProperty(0, ExtendedRenderingState.NEAR, -10e5); // CroppingPanel.getNear(image));
//		rs.setChannelProperty(0, ExtendedRenderingState.FAR,   10e5); // CroppingPanel.getFar(image));

//		Dimension outsize = new Dimension(image.getWidth(), image.getHeight());
//		double mag = image.getCanvas().getMagnification();
//		outsize.width = (int)Math.round(outsize.width * mag);
//		outsize.height = (int)Math.round(outsize.height * mag);
//
//		setOutputSize(outsize.width, outsize.height);

		renderer.getBoundingBox().setVisible(bb);
		renderer.getScalebar().setVisible(scalebar > 0);
		renderer.getScalebar().setLength(scalebar);
		renderer.getScalebar().setPosition(scalebarPos);
		renderer.getScalebar().setOffset(scalebarOffs);
		int nChannels = renderer.getNChannels();
		ExtendedRenderingState kf = renderer.getRenderingState().clone();
		boolean[] useLights = kf.useLights();
		boolean[] useLUT = kf.useLUT();

		switch(algorithm) {
		case INDEPENDENT_TRANSPARENCY:
			renderer.setProgram(OpenCLProgram.makeSource(nChannels, false, false, false, useLights, useLUT));
			break;
		case COMBINED_TRANSPARENCY:
			renderer.setProgram(OpenCLProgram.makeSource(nChannels, false, true, false, useLights, useLUT));
			break;
		case MAXIMUM_INTENSITY:
			renderer.setProgram(OpenCLProgram.makeSource(nChannels, false, false, true, useLights, useLUT));
			break;
		}

		Animator animator = new Animator(renderer);
		AtomicBoolean finished = new AtomicBoolean(false);
		animator.addAnimationListener(new Animator.Listener() {
			@Override
			public void animationFinished() {
				finished.set(true);
			}
		});
		try {
			animator.render(animation);
		} catch(Exception e) {
			IJ.handleException(e);
			return;
		}
		// wait for rendering
		while(!finished.get()) {
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		OpenCLRaycaster.close();
	}

	public static void main(String... args) throws IOException {
		new ij.ImageJ();
		ImagePlus imp = IJ.openImage("D:\\flybrain.green.tif");
		imp.show();

		BatchRaycaster cr = new BatchRaycaster();
		cr.setup("", imp);
		cr.run(null);
	}
}
