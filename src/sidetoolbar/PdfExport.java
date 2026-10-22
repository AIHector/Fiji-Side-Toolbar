package sidetoolbar;

import ij.IJ;
import ij.ImagePlus;
import ij.io.SaveDialog;

import java.awt.image.BufferedImage;
import java.io.FileOutputStream;

/**
 * Saves an image as a PDF with the iText library that Fiji ships (jars/itextpdf-*.jar): one page per plane, each
 * page the size of the image, showing it as displayed (LUT, display range, overlay).
 */
final class PdfExport {

	private PdfExport() { }

	static void save(ImagePlus imp) {
		String base = imp.getTitle().replaceFirst("\\.[A-Za-z0-9]+$", "");
		SaveDialog sd = new SaveDialog("Save as PDF", base, ".pdf");
		if (sd.getFileName() == null) return;
		final String path = sd.getDirectory() + sd.getFileName();
		new Thread(() -> {
			try {
				write(imp, path);
				IJ.showStatus("Saved " + path);
			} catch (NoClassDefFoundError e) {
				IJ.error("Side Toolbar", "PDF export needs the iText library (jars/itextpdf-*.jar), which this Fiji does not have.");
			} catch (Throwable t) {
				IJ.error("Side Toolbar", "Could not save the PDF:\n" + t);
			}
		}, "Side Toolbar: PDF").start();
	}

	static void write(ImagePlus imp, String path) throws Exception {
		int n = imp.getStackSize(), current = imp.getCurrentSlice();
		com.itextpdf.text.Document doc = new com.itextpdf.text.Document(
			new com.itextpdf.text.Rectangle(imp.getWidth(), imp.getHeight()), 0, 0, 0, 0);
		FileOutputStream out = new FileOutputStream(path);
		try {
			com.itextpdf.text.pdf.PdfWriter.getInstance(doc, out);
			doc.open();
			for (int i = 1; i <= n; i++) {
				IJ.showProgress(i, n);
				if (n > 1) imp.setSliceWithoutUpdate(i);
				BufferedImage bi = imp.flatten().getBufferedImage(); // as displayed, overlay included
				com.itextpdf.text.Image page = com.itextpdf.text.Image.getInstance(bi, null);
				page.scaleToFit(imp.getWidth(), imp.getHeight());
				page.setAbsolutePosition(0, 0);
				if (i > 1) doc.newPage();
				doc.add(page);
			}
		} finally {
			doc.close();
			out.close();
			if (n > 1) imp.setSlice(current);
		}
	}
}
