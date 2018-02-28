package application.ui;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.daisy.braille.utils.api.table.TableCatalog;
import org.daisy.braille.utils.pef.TextHandler;
import org.daisy.dotify.studio.api.Editor;
import org.daisy.streamline.api.identity.IdentityProvider;
import org.daisy.streamline.api.media.AnnotatedFile;
import org.daisy.streamline.api.tasks.TaskGroupFactoryMaker;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import application.common.FeatureSwitch;
import application.common.Settings;
import application.l10n.Messages;
import application.ui.about.AboutView;
import application.ui.imports.ImportBrailleView;
import application.ui.prefs.PreferencesView;
import application.ui.preview.EditorWrapperController;
import application.ui.preview.server.StartupDetails;
import application.ui.search.SearchController;
import application.ui.template.TemplateView;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SingleSelectionModel;
import javafx.scene.control.SplitPane;
import javafx.scene.control.SplitPane.Divider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.FileChooser.ExtensionFilter;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Provides the controller for the main view.
 * @author Joel Håkansson
 *
 */
public class MainController {
	private static final Logger logger = Logger.getLogger(MainController.class.getCanonicalName());
	private static final int CONSOLE_MESSAGE_LIMIT = 500;
	@FXML private BorderPane root;
	@FXML private TabPane tabPane;
	@FXML private TabPane toolsPane;
	@FXML private SplitPane splitPane;
	@FXML private SplitPane verticalSplitPane;
	@FXML private BorderPane consoleRoot;
	@FXML private WebView console;
	@FXML private ScrollPane consoleScroll;
	@FXML private MenuItem closeMenuItem;
	@FXML private MenuItem exportMenuItem;
	@FXML private MenuItem saveMenuItem;
	@FXML private MenuItem saveAsMenuItem;
	@FXML private MenuItem refreshMenuItem;
	@FXML private MenuItem openInBrowserMenuItem;
	@FXML private MenuItem embossMenuItem;
	@FXML private CheckMenuItem showSearchMenuItem;
	@FXML private CheckMenuItem showConsoleMenuItem;
	@FXML private ToggleButton scrollLockButton;
	@FXML private MenuItem nextEditorViewMenuItem;
	@FXML private MenuItem previousEditorViewMenuItem;
	@FXML private MenuItem toggleViewMenuItem;
	@FXML private MenuItem viewingModeMenuItem;
	@FXML private MenuItem activateViewMenuItem;
	private final double dividerPosition = 0.2;
	private Tab searchTab;
	private Tab helpTab;
	private ExecutorService exeService;
	private double[] verticalDividerPositions;
	private BooleanProperty canEmboss;
	private BooleanProperty canExport;
	private BooleanProperty canSave;
	private BooleanProperty canToggleView;
	private StringProperty urlProperty;
	static final KeyCombination CTRL_F4 = new KeyCodeCombination(KeyCode.F4, KeyCombination.CONTROL_DOWN);

	@FXML void initialize() {
		toolsPane.addEventHandler(KeyEvent.KEY_RELEASED, ev-> {
			if (CTRL_F4.match(ev)) {
				Tab t = toolsPane.getSelectionModel().getSelectedItem();
				if (t!=null) {
					toolsPane.getTabs().remove(t);
				}
				adjustToolsArea();
				ev.consume();
			}
		});
		tabPane.addEventHandler(KeyEvent.KEY_RELEASED, ev -> {
			if (CTRL_F4.match(ev)) {
				Tab t = tabPane.getSelectionModel().getSelectedItem();
				if (t!=null) {
					// TODO: don't remove the tab directly, ask it to close. 
					// See https://github.com/brailleapps/dotify-studio/issues/48
					// Event.fireEvent(t, new Event(Tab.TAB_CLOSE_REQUEST_EVENT));
					tabPane.getTabs().remove(t);
				}
				ev.consume();
			}
		});
		root.setOnDragOver(event->{
			Dragboard db = event.getDragboard();
			if (db.hasFiles() && canDropFiles(db.getFiles())) {
				event.acceptTransferModes(TransferMode.COPY);
			} else {
				event.consume();
			}
		});
		root.setOnDragDropped(event -> {
			Dragboard db = event.getDragboard();
			boolean success = false;
			if (db.hasFiles()) {
				success = true;
				for (File file : db.getFiles()) {
					Platform.runLater(() -> {
						if (file.getName().endsWith(".pef")) {
							addTab(file);
						} else {
							selectTemplateAndOpen(file);
						}
					});
				}
			}
			event.setDropCompleted(success);
			event.consume();
		});

		clearConsole();
		
		console.setOnDragOver(event->event.consume());
		exeService = Executors.newWorkStealingPool();
		System.setOut(new PrintStream(new ConsoleStream("out")));
		System.setErr(new PrintStream(new ConsoleStream("err")));
		try {
			//this is done to reinitialize the console logger so that it redirects to the new print streams set above
			LogManager.getLogManager().readConfiguration();
		} catch (SecurityException | IOException e) {
			e.printStackTrace();
		}
		canEmboss = new SimpleBooleanProperty();
		canExport = new SimpleBooleanProperty();
		canSave = new SimpleBooleanProperty();
		canToggleView = new SimpleBooleanProperty();
		urlProperty = new SimpleStringProperty();
		//add menu bindings
		setMenuBindings();
	}
	
	private void setMenuBindings() {
		tabPane.getSelectionModel().selectedItemProperty().addListener((o, ov, nv)->{
			canEmboss.unbind();
			canExport.unbind();
			canSave.unbind();
			canToggleView.unbind();
			urlProperty.unbind();
			if (nv!=null && nv.getContent() instanceof Editor) {
				Editor p = ((Editor)nv.getContent());
				canEmboss.bind(p.canEmbossProperty());
				canExport.bind(p.canExportProperty());
				canSave.bind(p.canSaveProperty());
				canToggleView.bind(p.toggleViewProperty());
				urlProperty.bind(p.urlProperty());
			} else {
				canEmboss.set(false);
				canExport.set(false);
				canSave.set(false);
				canToggleView.set(false);
				urlProperty.set(null);
			}
		});
		BooleanBinding noTabBinding = tabPane.getSelectionModel().selectedItemProperty().isNull();
		BooleanBinding noTabExceptHelpBinding = noTabBinding.or(
				tabPane.getSelectionModel().selectedItemProperty().isEqualTo(helpTab)
		);
		closeMenuItem.disableProperty().bind(noTabBinding);
		exportMenuItem.disableProperty().bind(noTabExceptHelpBinding.or(canExport.not()));
		saveMenuItem.disableProperty().bind(noTabExceptHelpBinding.or(canSave.not()));
		nextEditorViewMenuItem.disableProperty().bind(noTabBinding.or(
					Bindings.size(tabPane.getTabs()).lessThan(2))
				);
		previousEditorViewMenuItem.disableProperty().bind(noTabBinding.or(
					Bindings.size(tabPane.getTabs()).lessThan(2))
				);
		toggleViewMenuItem.disableProperty().bind(noTabExceptHelpBinding.or(canToggleView.not()));
		viewingModeMenuItem.disableProperty().bind(noTabExceptHelpBinding.or(canToggleView.not()));
		activateViewMenuItem.disableProperty().bind(noTabExceptHelpBinding);
		saveAsMenuItem.disableProperty().bind(noTabExceptHelpBinding);
		refreshMenuItem.disableProperty().bind(noTabBinding);
		openInBrowserMenuItem.disableProperty().bind(noTabBinding.or(urlProperty.isNull()));
		embossMenuItem.disableProperty().bind(noTabExceptHelpBinding.or(canEmboss.not()));
	}
	
	private static boolean canDropFiles(List<File> files) {
		List<String> exts = newImportExtensionStream()
			.map(val -> "."+val.toLowerCase())
			.collect(Collectors.toList());
		for (File f : files) {
			if (!(f.getName().endsWith(".pef")||exts.contains(getExtension(f.getName().toLowerCase())))) {
				return false;
			}
		}
		return true;
	}
	
	private static String getExtension(String filename) {
		int ix = filename.lastIndexOf('.');
		if (ix<0) {
			return "";
		} else {
			return filename.substring(ix);
		}
	}
	
	@FXML void clearConsole() {
		synchronized (console) {
			console.getEngine().loadContent("<html><body></body></html>");
			console.getEngine().setUserStyleSheetLocation(this.getClass().getResource("resource-files/console.css").toString());
		}
	}
	
	@FXML void showHideConsole() {
		if (showConsoleMenuItem.isSelected()) {
			verticalSplitPane.getItems().add(consoleRoot);
			verticalSplitPane.setDividerPositions(verticalDividerPositions);
		} else {
			verticalDividerPositions = verticalSplitPane.getDividerPositions();
			verticalSplitPane.getItems().remove(consoleRoot);
		}
	}

	private class ConsoleStream extends OutputStream {
		ByteArrayOutputStream bytes;
		private final String name;
		
		ConsoleStream(String name) {
			Objects.requireNonNull(name);
			this.bytes = new ByteArrayOutputStream();
			this.name = name;
		}
		
		void write(String s) {
			Platform.runLater(()->{
				synchronized (console) {
					Document doc = console.getEngine().getDocument();
					org.w3c.dom.Node body = doc.getElementsByTagName("body").item(0);
					Element p = doc.createElement("p");
					p.setAttribute("class", name);
					p.appendChild(doc.createTextNode(s));
					body.appendChild(p);
					while (body.getChildNodes().getLength()>CONSOLE_MESSAGE_LIMIT) {
						body.removeChild(body.getChildNodes().item(0));
					}
					if (!scrollLockButton.isSelected()) {
						console.getEngine().executeScript("window.scrollTo(0, document.body.scrollHeight);");
					}
				}
			});
		}
		
		@Override
		public void write(int b) throws IOException {
			if (b=='\n') {
				bytes.write('\n');
				write(new String(bytes.toByteArray()));
				bytes.reset();
			} else if (b!='\r') {
				bytes.write(b);
			}
		}
	}
	
	void openArgs(StartupDetails args) {
		Platform.runLater(()->addTab(args.getFile().getName(), args));
	}
	
	private Optional<Editor> getSelectedPreview() {
		return Optional.ofNullable(tabPane.getSelectionModel().getSelectedItem())
				.map(t->t.getContent())
				.filter(n->(n instanceof Editor))
				.map(n->(Editor)n);
	}
	
	@FXML void toggleEditor() {
		getSelectedPreview().ifPresent(p->p.toggleView());
	}
	
	@FXML void toggleViewingMode() {
		getSelectedPreview().ifPresent(p->p.toggleViewingMode());
	}
	
	@FXML void nextEditor() {
		SingleSelectionModel<Tab> select = tabPane.getSelectionModel();
		if (select.getSelectedIndex()>=tabPane.getTabs().size()-1) {
			select.selectFirst();
		} else {
			select.selectNext();
		}
	}
	
	@FXML void previousEditor() {
		SingleSelectionModel<Tab> select = tabPane.getSelectionModel();
		if (select.getSelectedIndex()<1) {
			select.selectLast();
		} else {
			tabPane.getSelectionModel().selectPrevious();
		}
	}
	
	@FXML void activateView() {
		getSelectedPreview().ifPresent(v->v.activate());
	}

    @FXML void refresh() {
		Tab t = tabPane.getSelectionModel().getSelectedItem();
		if (t!=null) {
			javafx.scene.Node node = t.getContent();
			if (node instanceof WebView) {
				((WebView) node).getEngine().reload();
			} else if (node instanceof Editor) {
				((Editor)node).reload();
			}
		}
    }
    
    @FXML void openInBrowser() {
		if (Desktop.isDesktopSupported()) {
			Tab t = tabPane.getSelectionModel().getSelectedItem();
			if (t!=null) {
				javafx.scene.Node node = t.getContent();
				if (node instanceof WebView) {
					new Thread(()->{
						// Wrapping this in a new thread in order to fix
						// https://github.com/brailleapps/dotify-studio/issues/44
						try {
							Desktop.getDesktop().browse(new URI(((WebView) node).getEngine().getLocation()));
						} catch (IOException | URISyntaxException e) {
							// fail silently
						}
					}).start();
				} else if (node instanceof Editor) {
					((Editor)t.getContent()).getURL().ifPresent(url->{
						new Thread(()->{
							// Wrapping this in a new thread in order to fix
							// https://github.com/brailleapps/dotify-studio/issues/44
							try {
								Desktop.getDesktop().browse(new URI(url));
							} catch (IOException | URISyntaxException e) {
								// fail silently
							}
						}).start();
					});
				}
			}
		}
	}
    
    @FXML void emboss() {
    	Tab t = tabPane.getSelectionModel().getSelectedItem();
		if (t!=null) {
			Editor controller = ((Editor)t.getContent());
			if (controller.canEmboss()) {
				Platform.runLater(()->{
					controller.showEmbossDialog();
				});
			}
		}
    }
    
    @FXML void closeTab() {
    	Tab t = tabPane.getSelectionModel().getSelectedItem();
		if (t!=null) {
			tabPane.getTabs().remove(t);
		}
    }
    
    @FXML void save() {
    	Tab t = tabPane.getSelectionModel().getSelectedItem();
		if (t!=null) {
			Editor controller = ((Editor)t.getContent());
			if (controller.canSave()) {
				controller.save();
			}
		}
    }
    
    @FXML void saveAs() {
    	Tab t = tabPane.getSelectionModel().getSelectedItem();
		if (t!=null && t.getContent() instanceof EditorWrapperController) {
			EditorWrapperController controller = ((EditorWrapperController)t.getContent());
			// display save dialog
			Window stage = root.getScene().getWindow();
	    	FileChooser fileChooser = new FileChooser();
	    	fileChooser.setTitle(Messages.TITLE_SAVE_AS_DIALOG.localize());
	    	fileChooser.getExtensionFilters().addAll(controller.getSaveAsFilters());
	    	Settings.getSettings().getLastSavePath().ifPresent(v->fileChooser.setInitialDirectory(v));
	    	File selected = fileChooser.showSaveDialog(stage);
	    	if (selected!=null) {
	    		Settings.getSettings().setLastSavePath(selected.getParentFile());
				try {
					if (controller.saveAs(selected)) {
						// stop watching
						controller.closing();
						// update contents of tab
						setTab(t, selected.getName(), StartupDetails.open(selected), controller.getOptions());
						// TODO: Restore document position
					}
				} catch (IOException e) {
					Alert alert = new Alert(AlertType.ERROR, e.getMessage(), ButtonType.OK);
		    		alert.showAndWait();
				}
	    	}
		}
    }
    
    @FXML void toggleSearchArea() {
    	ObservableList<Divider> dividers = splitPane.getDividers();
    	//TODO: observe changes and restore to that value
    	if (dividers.get(0).getPosition()>dividerPosition/2) {
    		splitPane.setDividerPosition(0, 0);
    		/*
    		expandButton.setText(">");
    		listView.setVisible(false);
    		searchFor.setVisible(false);*/
    	} else {
    		//expandButton.setText("<");
    		splitPane.setDividerPosition(0, dividerPosition);
    		/*
    		listView.setVisible(true);
    		searchFor.setVisible(true);*/
    	}
    }
    
    @FXML void openPreferences() {
		PreferencesView dialog = new PreferencesView();
		dialog.initOwner(root.getScene().getWindow());
		dialog.initModality(Modality.APPLICATION_MODAL);
		dialog.showAndWait();
		File generated = dialog.generatedTestFile();
		if (generated!=null) {
			addTab(generated.getName(), StartupDetails.open(generated));
		}
    }
    
    @FXML void openAbout() {
		AboutView dialog = new AboutView();
		dialog.initOwner(root.getScene().getWindow());
		dialog.initModality(Modality.APPLICATION_MODAL); 
		dialog.showAndWait();
    }
    
    @FXML void showHideSearch() {
		if (showSearchMenuItem.isSelected()) {
			if (searchTab==null || !toolsPane.getTabs().contains(searchTab)) {
				SearchController controller = new SearchController();
				controller.setOnOpen(book -> addTab(new File(book.getBook().getURI())));
				searchTab = addTabToTools(controller, Messages.TAB_SEARCH.localize());
			} else {
				//focus
				toolsPane.getSelectionModel().select(searchTab);
				if (splitPane.getDividerPositions()[0]<dividerPosition/3) {
					splitPane.setDividerPosition(0, dividerPosition);
				}
			}
		} else {
			toolsPane.getTabs().remove(searchTab);
			adjustToolsArea();
		}
	}

    private Tab addTabToTools(Parent component, String title) {
        Tab tab = new Tab();
        if (title!=null) {
        	tab.setText(title);
        }
        tab.setContent(component);
        tab.setOnClosed(ev -> {
        	adjustToolsArea();
        });
        if (toolsPane.getTabs().size()==0) {
    		splitPane.setDividerPosition(0, dividerPosition);
    	}
        toolsPane.getTabs().add(tab);
        toolsPane.getSelectionModel().select(tab);
        return tab;
    }
    
    private void adjustToolsArea() {
    	if (toolsPane.getTabs().size()==0) {
    		splitPane.setDividerPosition(0, 0);
    	}
    	showSearchMenuItem.setSelected(toolsPane.getTabs().contains(searchTab));
    }
    
    @FXML void showOpenDialog() {
    	Window stage = root.getScene().getWindow();
    	FileChooser fileChooser = new FileChooser();
    	fileChooser.setTitle(Messages.TITLE_OPEN_DIALOG.localize());
		if (FeatureSwitch.OPEN_OTHER_TYPES.isOn()) {
			List<String> exts = Arrays.asList("*.pef", "*.xml", "*.txt", "*.html", "*.obfl");
			List<ExtensionFilter> filters = exts.stream()
					.map(ext -> new ExtensionFilter(toFilesDesc(ext), ext))
					.collect(Collectors.toList());
			fileChooser.getExtensionFilters().add(new ExtensionFilter(Messages.EXTENSION_FILTER_SUPPORTED_FILES.localize(), exts));
			fileChooser.getExtensionFilters().addAll(filters);
		} else {
			fileChooser.getExtensionFilters().add(new ExtensionFilter(Messages.EXTENSION_FILTER_FILE.localize("PEF"), "*.pef"));
		}
    	Settings.getSettings().getLastOpenPath().ifPresent(v->fileChooser.setInitialDirectory(v));
    	File selected = fileChooser.showOpenDialog(stage);
    	if (selected!=null) {
    		Settings.getSettings().setLastOpenPath(selected.getParentFile());
    		addTab(selected);
    	}
    }

    @FXML void showImportBrailleDialog() {
    	Window stage = root.getScene().getWindow();
    	FileChooser fileChooser = new FileChooser();
    	fileChooser.setTitle(Messages.TITLE_IMPORT_BRAILLE_TEXT_DIALOG.localize());
    	File selected = fileChooser.showOpenDialog(stage);
    	if (selected!=null) {
    		ImportBrailleView brailleView = new ImportBrailleView(selected);
    		brailleView.initOwner(root.getScene().getWindow());
    		brailleView.initModality(Modality.APPLICATION_MODAL);
    		brailleView.showAndWait();
    		Map<String, String> settings = brailleView.getOptions();
    		if (settings!=null) {
				try {
					File output = File.createTempFile(selected.getName(), ".pef");
					output.deleteOnExit();
			    	Task<Void> importTask = new Task<Void>(){
						@Override
						protected Void call() throws Exception {
							TextHandler.with(selected, output, TableCatalog.newInstance())
								.options(settings)
								.parse();
							return null;
						}
			    	};
			    	importTask.setOnFailed(e->{
			    		logger.log(Level.WARNING, "Import failed.", importTask.getException());
			    		Alert alert = new Alert(AlertType.ERROR, importTask.getException().toString(), ButtonType.OK);
			    		alert.showAndWait();
			    	});
			    	importTask.setOnSucceeded(e->{
			    		Platform.runLater(()->{
			    			addTab(output);
			    		});
			    	});
			    	exeService.submit(importTask);
				} catch (IOException e1) {
					logger.log(Level.WARNING, "Failed to create temporary file.", e1);
				}
    		}
    	}
    }
    
    /**
     * Returns a new stream with unique input formats.
     * @return
     */
    private static Stream<String> newImportExtensionStream() {
    	return TaskGroupFactoryMaker.newInstance().listAll().stream()
			.filter(spec ->
				// Currently, this can be viewed as an identity conversion, which isn't supported by the task system.
				// TODO: Perhaps support this as a special case in this code instead (just open the file without going through the task system).
				!"pef".equals(spec.getInputFormat())
				// Not all formats in this list are actually extensions.
				// TODO: Filter these out here until the TaskGroupFactory provides extensions separately. 
				&& !"dtbook".equals(spec.getInputFormat()) // use xml instead
				&& !"text".equals(spec.getInputFormat())) // use txt instead
			.map(spec -> spec.getInputFormat())
			.distinct();
    }

    @FXML void showImportDialog() {
    	Window stage = root.getScene().getWindow();
    	FileChooser fileChooser = new FileChooser();
    	fileChooser.setTitle(Messages.TITLE_IMPORT_SOURCE_DOCUMENT_DIALOG.localize());
		List<String> exts = newImportExtensionStream()
				.map(val -> "*."+val)
				.collect(Collectors.toList()); 
		fileChooser.getExtensionFilters().add(new ExtensionFilter(Messages.EXTENSION_FILTER_SUPPORTED_FILES.localize(), exts));
		// All extensions are individually as well
		// TODO: additional information from the TaskGroupFactory about the formats (i.e. descriptions) would be useful for this list
		fileChooser.getExtensionFilters().addAll(
				exts.stream()
				.map(ext->new ExtensionFilter(toFilesDesc(ext), ext))
				.collect(Collectors.toList())
				);
		fileChooser.getExtensionFilters().add(new ExtensionFilter(Messages.EXTENSION_FILTER_ALL_FILES.localize(), "*.*"));
		Settings.getSettings().getLastOpenPath().ifPresent(v->fileChooser.setInitialDirectory(v));
    	File selected = fileChooser.showOpenDialog(stage);
    	if (selected!=null) {
    		Settings.getSettings().setLastOpenPath(selected.getParentFile());
    		selectTemplateAndOpen(selected);
    	}
    }
    
    private static String toFilesDesc(String ext) {
    	String ext2 = ext.startsWith("*.")?ext.substring(2):ext;
    	return Messages.EXTENSION_FILTER_FILE.localize(ext2.length()>3
    			?ext2.substring(0, 1).toUpperCase() + ext2.substring(1)
    			:ext2.toUpperCase());
    }
    
    private void selectTemplateAndOpen(File selected) {
		// choose template
		TemplateView dialog = new TemplateView(selected);
		if (dialog.hasTemplates()) {
			dialog.initOwner(root.getScene().getWindow());
			dialog.initModality(Modality.APPLICATION_MODAL); 
			dialog.showAndWait();
		}

		// convert then add tab
		addSourceTab(selected, dialog.getSelectedConfiguration());
    }
    
    @FXML void exportFile() {
    	Tab t = tabPane.getSelectionModel().getSelectedItem();
		if (t!=null) {
			Editor controller = ((Editor)t.getContent());
			if (controller.canExport()) {
		    	Window stage = root.getScene().getWindow();
		    	FileChooser fileChooser = new FileChooser();
		    	fileChooser.setTitle(Messages.TITLE_EXPORT_DIALOG.localize());
		    	Settings.getSettings().getLastSavePath().ifPresent(v->fileChooser.setInitialDirectory(v));
		    	File selected = fileChooser.showSaveDialog(stage);
		    	if (selected!=null) {
		    		Settings.getSettings().setLastSavePath(selected.getParentFile());
			    	Task<Void> exportTask = new Task<Void>() {
						@Override
						protected Void call() throws Exception {
							controller.export(selected);
							return null;
						}
			    	};
			    	exportTask.setOnFailed(e->{
			    		exportTask.getException().printStackTrace();
			    		Alert alert = new Alert(AlertType.ERROR, exportTask.getException().toString(), ButtonType.OK);
			    		alert.showAndWait();
			    	});
			    	exeService.submit(exportTask);
				}	
	    	}
		}
    }

	@FXML void closeApplication() {
		Stage stage = ((Stage)root.getScene().getWindow());
		stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
	}

	@FXML void openHelpTab() {
		// if the tab is not visible, recreate it (this is a workaround for https://github.com/brailleapps/dotify-studio/issues/20)
		Tab old = helpTab;
		if (helpTab==null || !tabPane.getTabs().contains(helpTab)) {
			WebView wv = new WebView();
			wv.setOnDragOver(event->event.consume());
			helpTab = new Tab(Messages.TAB_HELP_CONTENTS.localize(), wv);
			helpTab.setGraphic(buildImage(this.getClass().getResource("resource-files/help.png")));
			String helpURL = getHelpURL();
			if (helpURL!=null) {
				WebEngine engine = wv.getEngine();
				engine.load(helpURL);
			} else {
				wv.getEngine().loadContent("<html><body><p>"+Messages.ERROR_FAILED_TO_LOAD_HELP.localize()+"</p></body></html>");
			}
		}
		if (!tabPane.getTabs().contains(helpTab)) {
			tabPane.getTabs().add(helpTab);
		}
		tabPane.getSelectionModel().select(helpTab);
		if (helpTab!=old) {
			setMenuBindings();
		}
	}

	boolean confirmShutdown() {
		if (isAnyModified()) {
			Alert alert = new Alert(AlertType.CONFIRMATION, Messages.MESSAGE_CONFIRM_QUIT_UNSAVED_CHANGES.localize(), ButtonType.YES, ButtonType.CANCEL);
			Optional<ButtonType> res = alert.showAndWait();
			return res.map(v->(Boolean)v.equals(ButtonType.YES)).orElse(false);
		} else {
			return true;
		}
	}

	private boolean isAnyModified() {
		return tabPane.getTabs().stream()
				.map(t->t.getContent())
				.filter(n->(n instanceof Editor))
				.map(n->(Editor)n)
				.anyMatch(e->e.isModified());
	}
	
	private static ImageView buildImage(URL url) {
		Image i = new Image(url.toString());
		ImageView imageView = new ImageView();
		imageView.setImage(i);
		return imageView;
	}
	
	private String getHelpURL() {
		try {
			File codeLocation = new File((this.getClass().getProtectionDomain().getCodeSource().getLocation()).toURI()).getParentFile();
			File root = null;
			if (codeLocation.getName().equalsIgnoreCase("lib")) {
				root = codeLocation.getParentFile();
			} else {
				root = codeLocation;
			}
			File docs = new File(new File(root, "docs"), "Toc.html");
			if (docs.exists()) {
				return docs.toURI().toURL().toString();
			} else {
				return null;
			}
		} catch (Exception e) {
			return null;
		}
	}
    
    private void addTab(File f) { 
    	addTab(f.getName(), StartupDetails.open(f));
    }

    private void addTab(String title, StartupDetails args) {
        Tab tab = new Tab();
        setTab(tab, title, args, null);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }
    
    private void setTab(Tab tab, String title, StartupDetails args, Map<String, Object> options) {
    	if (title==null && args.getFile()!=null) {
        	title = args.getFile().getAbsolutePath();
        }
        if (title!=null) {
        	tab.setText(title);
        	setGraphic(title, tab);
        }
        setupEditor(tab, args.getFile(), options);
    }
    
	private void setGraphic(String fileName, Tab t) {
		boolean source = !fileName.endsWith(".pef");
		t.setGraphic(buildImage(this.getClass()
				.getResource(source ? "resource-files/source-doc.png" : "resource-files/braille-doc.png")));
	}
    
    private void addSourceTab(File source, Map<String, Object> options) {
        Tab tab = new Tab();
        setGraphic(source.getName(), tab);
        tab.setText(source.getName());
        setupEditor(tab, source, options);
        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
    }
    
    private void setupEditor(Tab tab, File source, Map<String, Object> options) {
		AnnotatedFile ai = IdentityProvider.newInstance().identify(source);
		EditorWrapperController prv = EditorWrapperController.newInstance(ai, options);
		tab.setOnClosed(ev ->  {
			prv.closing();
		});
		tab.setContent(prv);
		tab.setOnCloseRequest(ev->{
			if (((Editor)tab.getContent()).isModified()) {
				Alert alert = new Alert(AlertType.CONFIRMATION, Messages.MESSAGE_CONFIRM_CLOSE_UNSAVED_CHANGES.localize(source.getName()), ButtonType.YES, ButtonType.CANCEL);
				Optional<ButtonType> res = alert.showAndWait();
				if (res.map(v->(Boolean)!v.equals(ButtonType.YES)).orElse(true)) {
					ev.consume();
				}
			}
		});
		prv.modifiedProperty().addListener((o, ov, nv)->{
			String modified = "*";
			String t = tab.getText();
			if (nv) {
				if (!t.startsWith(modified)) {
					tab.setText(modified + tab.getText());
				}
			} else {
				if (t.startsWith(modified)) {
					tab.setText(t.substring(modified.length()));
				}
			}
		});
	}

}