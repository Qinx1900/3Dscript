package animation2;

import java.awt.AWTEvent;
import java.awt.Button;
import java.awt.Choice;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Panel;
import java.awt.Point;
import java.awt.TextField;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Arrays;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.ImageCanvas;
import ij.measure.Calibration;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.LUT;

public class InteractiveRaycaster implements PlugInFilter {

	private ImagePlus image;
	private double[] min, max;
	private int[][] histo8;


	@Override
	public int setup(String arg, ImagePlus imp) {
		this.image = imp;
		return DOES_8G | DOES_16;
	}

	@Override
	public void run(ImageProcessor ip) {
		final LUT[] luts = image.isComposite() ?
				image.getLuts() : new LUT[] {image.getProcessor().getLut()};

		final int nC = image.getNChannels();

		calculateChannelMinAndMax();

		final RenderingSettings[] renderingSettings = new RenderingSettings[nC];

		final float[] pd = new float[] {
				(float)image.getCalibration().pixelWidth,
				(float)image.getCalibration().pixelHeight,
				(float)image.getCalibration().pixelDepth
		};

		final float[] fromCalib = Transform.fromCalibration(pd[0], pd[1], pd[2], 0, 0, 0, null);

		final float[] pdOut = new float[] {pd[0], pd[0], pd[0]}; // TODO phOut

		final float[] toTransform = Transform.fromCalibration(
				pdOut[0], pdOut[1], pdOut[2], 0, 0, 0, null);
		Transform.invert(toTransform);

		final AnimatorDialog gd = new AnimatorDialog("Interactive Raycaster");
		String[] channels = new String[nC];
		for(int i = 0; i < nC; i++)
			channels[i] = "Channel " + (i + 1);
		gd.addChoice("Channel", channels);
		final Choice channelChoice = (Choice)gd.getChoices().lastElement();

		for(int c = 0; c < nC; c++) {
			renderingSettings[c] = new RenderingSettings(
					(float)luts[c].min, (float)luts[c].max, 2,
					(float)luts[c].min, (float)luts[c].max, 1);
		}
		Color col = getLUTColor(luts[0]);
		final HistogramSlider histogramSlider = gd.addHistogramSlider(null, histo8[0], col, min[0], max[0], renderingSettings[0]);
		gd.addMessage("");

		int d = image.getNSlices();
		final float[] nearfar = new float[] {0, 2 * d};
		final DoubleSlider nearfarSlider = gd.addDoubleSlider(
				"near/far",
				new int[] {-5 * d, 5 * d},
				new int[] {Math.round(nearfar[0]), Math.round(nearfar[1])},
				new Color(255, 0, 0, 100));

		final DoubleSlider xRangeSlider = gd.addDoubleSlider(
				"x_range",
				new int[] {0, image.getWidth()},
				new int[] {0, image.getWidth()},
				new Color(255, 0, 0, 100));
		final DoubleSlider yRangeSlider = gd.addDoubleSlider(
				"y_range",
				new int[] {0, image.getHeight()},
				new int[] {0, image.getHeight()},
				new Color(255, 0, 0, 100));
		final DoubleSlider zRangeSlider = gd.addDoubleSlider(
				"z_range",
				new int[] {0, image.getNSlices()},
				new int[] {0, image.getNSlices()},
				new Color(255, 0, 0, 100));
//		gd.addSlider("near", -5 * d, 5 * d, nearfar[0]);
//		gd.addSlider("far", -5 * d, 5 * d, nearfar[1]);

		final float[] scale = new float[] {1};
		final float[] translation = new float[3];
		final float[] rotation = Transform.fromIdentity(null);

		final float[] rotcenter = new float[] {image.getWidth() * pd[0] / 2, image.getHeight() * pd[1] / 2, image.getNSlices() * pd[2] / 2};

		final RenderingThread worker = new RenderingThread(image, renderingSettings, Transform.fromIdentity(null), nearfar);
		gd.addNumericField("output_width", worker.out.getWidth(), 0);
		final TextField widthTF = (TextField)gd.getNumericFields().lastElement();
		gd.addNumericField("output_height", worker.out.getHeight(), 0);
		final TextField heightTF = (TextField)gd.getNumericFields().lastElement();

		Calibration cal = worker.out.getCalibration();
		cal.pixelWidth = pdOut[0] / scale[0];
		cal.pixelHeight = pdOut[1] / scale[0];
		cal.setUnit(image.getCalibration().getUnit());

		// TODO shutdown

		final Point mouseDown = new Point();
		final boolean[] isRotation = new boolean[] {false};

		final ImageCanvas canvas = worker.out.getCanvas();
		canvas.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				mouseDown.setLocation(e.getPoint());
				isRotation[0] = !e.isShiftDown();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				int dx = e.getX() - mouseDown.x;
				int dy = e.getY() - mouseDown.y;
				if(!isRotation[0]) {
					translation[0] += dx * pdOut[0] / scale[0];
					translation[1] += dy * pdOut[1] / scale[0];
				}
				else {
					float speed = 0.7f;
					if(e.isAltDown()) {
						if(Math.abs(dx) > Math.abs(dy))
							dy = 0;
						else
							dx = 0;
					}
					int ax = -Math.round(dx * speed);
					int ay =  Math.round(dy * speed);

					float[] rx = Transform.fromAngleAxis(new float[] {0, 1, 0}, ax * (float)Math.PI / 180f, null);
					float[] ry = Transform.fromAngleAxis(new float[] {1, 0, 0}, ay * (float)Math.PI / 180f, null);
					float[] r = Transform.mul(rx, ry);
//					float[] cinv = Transform.fromTranslation(-rotcenter[0], -rotcenter[1], -rotcenter[2], null);
//					float[] c = Transform.fromTranslation(rotcenter[0], rotcenter[1], rotcenter[2], null);
//					float[] rot = Transform.mul(c, Transform.mul(r, Transform.mul(cinv, rotation)));
					float[] rot = Transform.mul(r, rotation);

					System.arraycopy(rot, 0, rotation, 0, 12);
				}
			}
		});
		canvas.addMouseMotionListener(new MouseMotionListener() {
			@Override
			public void mouseDragged(MouseEvent e) {
				// translation
				if(!isRotation[0]) {
					System.out.println(e.getX() + ", " + e.getY());
					int dx = e.getX() - mouseDown.x;
					int dy = e.getY() - mouseDown.y;
					float[] inverse = calculateInverseTransform(
							scale[0],
							new float[] {translation[0] + dx * pdOut[0] / scale[0], translation[1] + dy * pdOut[1] / scale[0], translation[2]},
							rotation,
							rotcenter,
							fromCalib,
							toTransform);
					worker.push(renderingSettings, inverse, nearfar);
				}
				// rotation
				else {
					float speed = 0.7f;
					int dx = e.getX() - mouseDown.x;
					int dy = e.getY() - mouseDown.y;
					if(e.isAltDown()) {
						if(Math.abs(dx) > Math.abs(dy))
							dy = 0;
						else
							dx = 0;
					}
					int ax = -Math.round(dx * speed);
					int ay =  Math.round(dy * speed);

					IJ.showStatus(ax + "\u00B0" + ", " + ay + "\u00B0");

					float[] rx = Transform.fromAngleAxis(new float[] {0, 1, 0}, ax * (float)Math.PI / 180f, null);
					float[] ry = Transform.fromAngleAxis(new float[] {1, 0, 0}, ay * (float)Math.PI / 180f, null);

					float[] r = Transform.mul(rx, ry);
					float[] rot = Transform.mul(r, rotation);

					System.out.println(rot[3] + ", "+ rot[7] + ", " + rot[11]);
//					float[] cinv = Transform.fromTranslation(-rotcenter[0], -rotcenter[1], -rotcenter[2], null);
//					float[] c = Transform.fromTranslation(rotcenter[0], rotcenter[1], rotcenter[2], null);
//					float[] rot = Transform.mul(c, Transform.mul(r, Transform.mul(cinv, rotation)));
					float[] inverse = calculateInverseTransform(
							scale[0],
							translation,
							rot,
							rotcenter,
							fromCalib,
							toTransform);
					worker.push(renderingSettings, inverse, nearfar);
				}
			}

			@Override
			public void mouseMoved(MouseEvent e) {}
		});
		canvas.addMouseWheelListener(new MouseWheelListener() {
			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				int units = e.getWheelRotation();
				float factor = 1 + units * 0.2f;

				float[] centerinv =  new float[] {-e.getX() * pdOut[0] / scale[0], -e.getY() * pdOut[1] / scale[0], 0};

				scale[0] *= factor;

				float[] center = new float[] {e.getX() * pdOut[0],  e.getY() * pdOut[1], 0};

				float[] scaleM = Transform.fromScale(scale[0], null);
				float[] scaleInvM = Transform.fromScale(1 / scale[0], null);
				float[] centerM = Transform.fromTranslation(center[0], center[1], center[2], null);
				float[] centerInvM = Transform.fromTranslation(centerinv[0], centerinv[1], centerinv[2], null);

				float[] X = Transform.mul(Transform.mul(Transform.mul(scaleInvM, centerM), scaleM), centerInvM);
				System.out.println(Arrays.toString(X));
				translation[0] += X[3];
				translation[1] += X[7];
				translation[2] += X[11];


				float[] inverse = calculateInverseTransform(scale[0], translation, rotation, rotcenter, fromCalib, toTransform);
				worker.push(renderingSettings, inverse, nearfar);
				Calibration cal = worker.out.getCalibration();
				cal.pixelWidth = pdOut[0] / scale[0];
				cal.pixelHeight = pdOut[1] / scale[0];
			}
		});

//		final ImageWindow window = worker.out.getWindow();
//		final int extraWidth = window.getWidth() - canvas.getWidth();
//		final int extraHeight = window.getHeight() - canvas.getHeight();
//		window.addComponentListener(new ComponentAdapter() {
//			@Override
//			public void componentResized(ComponentEvent e) {
//				System.out.println("componentResized");
//				int tgtW = window.getWidth() - extraWidth;
//				int tgtH = window.getHeight() - extraHeight;
//
//				System.out.println(tgtW + ", "+ tgtH);
//
//				pdOut[0] = image.getWidth() * pd[0] / tgtW;
//				pdOut[1] = image.getWidth() * pd[0] / tgtW; // TODO phOut
//
//				final float[] tt = Transform.fromCalibration(
//						pdOut[0], pdOut[1], pdOut[2], 0, 0, 0, null);
//				Transform.invert(tt);
//				System.arraycopy(tt, 0, toTransform, 0, 12);
//				float[] inverse = calculateInverseTransform(scale[0], translation, rotation, fromCalib, toTransform);
//				worker.push(renderingSettings, inverse, nearfar, tgtW, tgtH);
//				Calibration cal = worker.out.getCalibration();
//				cal.pixelWidth = pdOut[0] / scale[0];
//				cal.pixelHeight = pdOut[1] / scale[0];
//
//			}
//		});

		gd.addDialogListener(new DialogListener() {
			@Override
			public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
				if(e != null && (e.getSource() == widthTF || e.getSource() == heightTF)) {
					int tgtW = Integer.parseInt(widthTF.getText());
					int tgtH = Integer.parseInt(heightTF.getText());
					pdOut[0] = image.getWidth() * pd[0] / tgtW;
					pdOut[1] = image.getWidth() * pd[0] / tgtW; // TODO phOut

					final float[] tt = Transform.fromCalibration(
							pdOut[0], pdOut[1], pdOut[2], 0, 0, 0, null);
					Transform.invert(tt);
					System.arraycopy(tt, 0, toTransform, 0, 12);

					float[] inverse = calculateInverseTransform(scale[0], translation, rotation, rotcenter, fromCalib, toTransform);
					worker.push(renderingSettings, inverse, nearfar, tgtW, tgtH);
					Calibration cal = worker.out.getCalibration();
					cal.pixelWidth = pdOut[0] / scale[0];
					cal.pixelHeight = pdOut[1] / scale[0];
					return true;
				}
				else if(e != null && e.getSource() == channelChoice) {
					int c = channelChoice.getSelectedIndex();

					Color col = getLUTColor(luts[c]);
					((AnimatorDialog)gd).getHistogramSliders().get(0).set(histo8[c], col, min[c], max[c], renderingSettings[c]);
					return true;
				}
				return false;
			}
		});

		nearfarSlider.addSliderChangeListener(new DoubleSlider.Listener() {

			@Override
			public void sliderChanged() {
				try {
					nearfar[0] = nearfarSlider.getMin();
					nearfar[1] = nearfarSlider.getMax();
				} catch(Throwable t) {
					t.printStackTrace();
				}
				float[] inverse = calculateInverseTransform(scale[0], translation, rotation, rotcenter, fromCalib, toTransform);
				worker.push(renderingSettings, inverse, nearfar);
			}
		});

		DoubleSlider.Listener rangeListener = new DoubleSlider.Listener() {
			@Override
			public void sliderChanged() {
				int x = xRangeSlider.getMin();
				int y = yRangeSlider.getMin();
				int z = zRangeSlider.getMin();
				int w = xRangeSlider.getMax() - x;
				int h = yRangeSlider.getMax() - y;
				int d = zRangeSlider.getMax() - z;
				float[] inverse = calculateInverseTransform(scale[0], translation, rotation, rotcenter, fromCalib, toTransform);
				worker.push(renderingSettings, inverse, nearfar, x, y, z, w, h, d);
			}
		};
		xRangeSlider.addSliderChangeListener(rangeListener);
		yRangeSlider.addSliderChangeListener(rangeListener);
		zRangeSlider.addSliderChangeListener(rangeListener);

		histogramSlider.addRenderingSettingsChangeListener(new RenderingSettingsChangeListener() {
			@Override
			public void renderingSettingsChanged() {
				float[] inverse = calculateInverseTransform(scale[0], translation, rotation, rotcenter, fromCalib, toTransform);
				worker.push(renderingSettings, inverse, nearfar);
			}
		});

		gd.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				worker.shutdown(); // TODO check that this works
			}
		});

//		final TreeMap<Integer, Keyframe> keyframes = new TreeMap<Integer, Keyframe>();
		Keyframe kf = createKeyframe(0, xRangeSlider, yRangeSlider, zRangeSlider, renderingSettings, rotation, translation, scale, nearfar);
//		keyframes.put(0, kf);
//		keyframes.put(99, createKeyframe(99, xRangeSlider, yRangeSlider, zRangeSlider, renderingSettings, rotation, translation, scale, nearfar));


		final Timelines timelines = new Timelines(renderingSettings.length, 0, 99);

		final String[] timelineNames = timelines.getNames();
		gd.addChoice("Timeline", timelineNames, timelineNames[0]);
		final Choice timelineChoice = (Choice)gd.getChoices().lastElement();
		final TimelineSlider timeline = gd.addTimelineSlider(timelines.get(0), 0);

		timelineChoice.addItemListener(new ItemListener() {
			@Override
			public void itemStateChanged(ItemEvent arg0) {
				CtrlPoints tl = timelines.get(timelineChoice.getSelectedIndex());
				timeline.set(tl);
			}
		});


		timeline.addTimelineListener(new TimelineSlider.Listener() {
			@Override
			public void currentTimepointChanged(int t) {
				Keyframe k = timelines.getInterpolatedFrame(t);
				for(int i = 0; i < renderingSettings.length; i++) {
					renderingSettings[i].set(k.renderingSettings[i]);
				}
				xRangeSlider.setMinAndMax(k.bbx, k.bbx + k.bbw);
				yRangeSlider.setMinAndMax(k.bby, k.bby + k.bbh);
				zRangeSlider.setMinAndMax(k.bbz, k.bbz + k.bbd);

				translation[0] = k.dx;
				translation[1] = k.dy;
				translation[2] = k.dz;

				scale[0] = k.scale;

				nearfar[0] = k.near;
				nearfar[1] = k.far;
				nearfarSlider.setMinAndMax(Math.round(k.near), Math.round(k.far));

				Transform.fromEulerAngles(rotation, new double[] {k.angleX, k.angleY, k.angleZ});

				float[] inverse = calculateInverseTransform(scale[0], translation, rotation, rotcenter, fromCalib, toTransform);
				worker.push(renderingSettings, inverse, nearfar, k.bbx, k.bby, k.bbz, k.bbw, k.bbh, k.bbd);
			}
		});
		timeline.setVisible(true); //TODO remove again

		Panel p = new Panel(new FlowLayout(FlowLayout.RIGHT));
		Button but = new Button("Set");
		but.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				int f = timeline.getCurrentFrame();
				timelines.recordFrame(createKeyframe(f,
						xRangeSlider,
						yRangeSlider,
						zRangeSlider,
						renderingSettings,
						rotation,
						translation,
						scale,
						nearfar));
				timeline.repaint();
			}
		});
		p.add(but);
		gd.addPanel(p);


		p = new Panel(new FlowLayout(FlowLayout.RIGHT));
		but = new Button("Animate");
		but.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				GenericDialog gd = new GenericDialog("");
				gd.addNumericField("#frames", 180, 0);
				gd.addNumericField("y_angle_increment", 2, 0);
				gd.addNumericField("x_angle_increment", 0, 0);
				gd.addCheckbox("Scroll_through", false);
				gd.addNumericField("Scroll_from", 0, 2);
				gd.addNumericField("Scroll_to", 0, 2);
				gd.addNumericField("dz", 1, 0);
				gd.showDialog();
				if(gd.wasCanceled())
					return;

				int nFrames = (int)gd.getNextNumber();
				int ax = (int)gd.getNextNumber();
				int ay = (int)gd.getNextNumber();
				boolean scrollThrough = gd.getNextBoolean();
				float scrollFrom = (float)gd.getNextNumber();
				float scrollTo = (float)gd.getNextNumber();
				float dz = (float)gd.getNextNumber();

				ImageStack stack = new ImageStack(worker.out.getWidth(), worker.out.getHeight());
				float[] inverse = null;
				for(int i = 0; i < nFrames; i++) {
					float[] rx = Transform.fromAngleAxis(new float[] {0, 1, 0}, ax * (float)Math.PI / 180f, null);
					float[] ry = Transform.fromAngleAxis(new float[] {1, 0, 0}, ay * (float)Math.PI / 180f, null);
					float[] r = Transform.mul(rx, ry);
//					float[] cinv = Transform.fromTranslation(-rotcenter[0], -rotcenter[1], -rotcenter[2], null);
//					float[] c = Transform.fromTranslation(rotcenter[0], rotcenter[1], rotcenter[2], null);
//					float[] rot = Transform.mul(c, Transform.mul(r, Transform.mul(cinv, rotation)));
					float[] rot = Transform.mul(r, rotation);
					System.arraycopy(rot, 0, rotation, 0, 12);

					inverse = calculateInverseTransform(
							scale[0],
							translation,
							rot,
							rotcenter,
							fromCalib,
							toTransform);
					stack.addSlice(worker.getRaycaster().renderAndCompose(inverse, renderingSettings, nearfar[0], nearfar[1]).getProcessor());
					IJ.showProgress(i + 1, nFrames);
				}
				if(scrollThrough) {
					int n = Math.round((scrollTo - scrollFrom) / dz) + 1;
					for(int i = 0; i < n; i++) {
						stack.addSlice(worker.getRaycaster().renderAndCompose(
								inverse, renderingSettings, scrollFrom + i * dz, nearfar[1]).getProcessor());
					}
					for(int i = n - 1; i >= 0; i--) {
						stack.addSlice(worker.getRaycaster().renderAndCompose(
								inverse, renderingSettings, scrollFrom + i * dz, nearfar[1]).getProcessor());
					}
				}
				ImagePlus anim = new ImagePlus(image.getTitle(), stack);
				anim.setCalibration(worker.out.getCalibration().copy());
				anim.show();
			}
		});
		p.add(but);

		but = new Button("Reset transformations");
		but.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				scale[0] = 1;
				translation[0] = translation[1] = translation[2] = 0;
				Transform.fromIdentity(rotation);
				float[] inverse = calculateInverseTransform(scale[0], translation, rotation, rotcenter, fromCalib, toTransform);
				worker.push(renderingSettings, inverse, nearfar);
			}
		});
		p.add(but);

		but = new Button("Reset rendering settings");
		but.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				for(int c = 0; c < nC; c++) {
					renderingSettings[c].alphaMin = (float)luts[c].min;
					renderingSettings[c].alphaMax = (float)luts[c].max;
					renderingSettings[c].alphaGamma = 2;
					renderingSettings[c].colorMin = (float)luts[c].min;
					renderingSettings[c].colorMax = (float)luts[c].max;
					renderingSettings[c].colorGamma = 1;
				}

				int c = channelChoice.getSelectedIndex();
				Color col = getLUTColor(luts[c]);
				gd.getHistogramSliders().get(0).set(histo8[c], col, min[c], max[c], renderingSettings[c]);
				float[] inverse = calculateInverseTransform(scale[0], translation, rotation, rotcenter, fromCalib, toTransform);
				worker.push(renderingSettings, inverse, nearfar);
			}
		});
		p.add(but);

		gd.addPanel(p);

		gd.setModal(false);
		gd.showDialog();
	}

	private Keyframe createKeyframe(int frame,
			DoubleSlider xRangeSlider,
			DoubleSlider yRangeSlider,
			DoubleSlider zRangeSlider,
			RenderingSettings[] renderingSettings,
			float[] rotation,
			float[] translation,
			float[] scale,
			float[] nearfar) {
		RenderingSettings[] rs = new RenderingSettings[renderingSettings.length];
		for(int i = 0; i < rs.length; i++)
			rs[i] = new RenderingSettings(renderingSettings[i]);
		int bbx = xRangeSlider.getMin();
		int bby = yRangeSlider.getMin();
		int bbz = zRangeSlider.getMin();
		int bbw = xRangeSlider.getMax() - bbx;
		int bbh = yRangeSlider.getMax() - bby;
		int bbd = zRangeSlider.getMax() - bbz;

		double[] eulerAngles = new double[3];
		Transform.guessEulerAngles(rotation, eulerAngles);
		return new Keyframe(
				frame, rs,
				nearfar[0], nearfar[1],
				scale[0],
				translation[0], translation[1], translation[2],
				eulerAngles[0], eulerAngles[1], eulerAngles[2],
				bbx, bby, bbz, bbw, bbh, bbd);
	}

	private Color getLUTColor(LUT lut) {
		int index = lut.getMapSize() - 1;
		int r = lut.getRed(index);
		int g = lut.getGreen(index);
		int b = lut.getBlue(index);
		//IJ.log(index+" "+r+" "+g+" "+b);
		if (r<100 || g<100 || b<100)
			return new Color(r, g, b);
		else
			return Color.black;
	}



	/**
	 * Calculates scale * translation * rotation
	 * @param scale
	 * @param translation
	 * @param rotation
	 */
	private float[] calculateInverseTransform(float scale, float[] translation, float[] rotation, float[] center, float[] fromCalib, float[] toTransform) {
		float[] scaleM = Transform.fromScale(scale, null);
		float[] transM = Transform.fromTranslation(translation[0], translation[1], translation[2], null);
		float[] centerM = Transform.fromTranslation(-center[0], -center[1], -center[2], null);

		float[] x = Transform.mul(rotation, centerM);
		Transform.applyTranslation(center[0], center[1], center[2], x);
		x = Transform.mul(Transform.mul(scaleM, transM), x);

		x = Transform.mul(x, fromCalib);
		x = Transform.mul(toTransform, x);

		Transform.invert(x);
		return x;
	}

	private void calculateChannelMinAndMax() {
		int nC = image.getNChannels();
		min = new double[nC];
		max = new double[nC];
		histo8 = new int[nC][];

		for(int c = 0; c < nC; c++) {
			min[c] = Double.POSITIVE_INFINITY;
			max[c] = Double.NEGATIVE_INFINITY;
			for(int z = 0; z < image.getNSlices(); z++) {
				int idx = image.getStackIndex(c + 1, z + 1, image.getT());
				ImageProcessor ip = image.getStack().getProcessor(idx);
				ImageStatistics stat = ImageStatistics.getStatistics(ip, ImageStatistics.MIN_MAX, null);
				min[c] = Math.min(min[c], stat.min);
				max[c] = Math.max(max[c], stat.max);
			}
			int wh = image.getWidth() * image.getHeight();
			int nBins = 256;
			histo8[c] = new int[nBins];
			double scale = nBins / (max[c] - min[c]);
			for(int z = 0; z < image.getNSlices(); z++) {
				int idx = image.getStackIndex(c + 1, z + 1, image.getT());
				ImageProcessor ip = image.getStack().getProcessor(idx);
				for(int i = 0; i < wh; i++) {
					float v = ip.getf(i);
					int index = (int)(scale * (v - min[c]));
					if(index >= nBins)
						index = nBins-1;
					histo8[c][index]++;
				}
			}
		}
	}

	public static void main(String... args) {
		new ij.ImageJ();
		String dir = "D:\\VLanger\\20161205-Intravital-Darm\\";
		String name = "cy5-shg-2p-maus3919-gecleart-20x-big-stack1.resampled.tif";
		// ImagePlus imp = IJ.openImage(dir + name);
		ImagePlus imp = IJ.openImage("D:\\flybrain.tif");
		// ImagePlus imp = IJ.openImage("D:\\MHoffmann\\20160126-Markus2.small.tif");
		// ImagePlus imp = IJ.openImage("/Users/bene/flybrain.tif");
		imp.show();

		InteractiveRaycaster cr = new InteractiveRaycaster();
		cr.setup("", imp);
		cr.run(null);
	}
}