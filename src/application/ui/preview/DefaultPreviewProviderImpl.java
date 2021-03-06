package application.ui.preview;

import org.daisy.dotify.studio.api.OpenableEditor;
import org.daisy.dotify.studio.api.PreviewProvider;
import org.daisy.streamline.api.media.FileDetails;

public class DefaultPreviewProviderImpl implements PreviewProvider {
	
	@Override
	public boolean supportsFormat(FileDetails format) {
		return FormatChecker.isPEF(format) || FormatChecker.isHTML(format) || FormatChecker.isText(format);
	}

	@Override
	public OpenableEditor newPreview(FileDetails selected) {
		if (FormatChecker.isPEF(selected)) {
			PreviewPefController prv = new PreviewPefController();
			return prv;
		} else if (FormatChecker.isHTML(selected)) {
			PreviewHtmlController prv = new PreviewHtmlController();
			return prv;
		} else if (FormatChecker.isText(selected)) {
			return new EditorController(true);
		}
		throw new RuntimeException();
	}

}
