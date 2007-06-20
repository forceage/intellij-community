package com.intellij.openapi.fileEditor.impl;

import com.intellij.AppTopics;
import com.intellij.ProjectTopics;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ex.ApplicationManagerEx;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.injected.VirtualFileDelegate;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager;
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.fileTypes.FileTypeEvent;
import com.intellij.openapi.fileTypes.FileTypeListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.FileStatusListener;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.ex.StatusBarEx;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.openapi.wm.impl.IdeFrameImpl;
import com.intellij.problems.WolfTheProblemSolver;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.List;

/**
 * @author Anton Katilin
 * @author Eugene Belyaev
 * @author Vladimir Kondratyev
 */
public final class FileEditorManagerImpl extends FileEditorManagerEx implements ProjectComponent, JDOMExternalizable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl");
  private static final Key<LocalFileSystem.WatchRequest> WATCH_REQUEST_KEY = Key.create("WATCH_REQUEST_KEY");

  private static final FileEditor[] EMPTY_EDITOR_ARRAY = new FileEditor[]{};
  private static final FileEditorProvider[] EMPTY_PROVIDER_ARRAY = new FileEditorProvider[]{};

  private final JPanel myPanels;
  public Project myProject;

  private final MergingUpdateQueue myQueue = new MergingUpdateQueue("FileEditorManagerUpdateQueue", 50, true, null);

  /**
   * Updates tabs colors
   */
  private final MyFileStatusListener myFileStatusListener;

  /**
   * Removes invalid myEditor and updates "modified" status.
   */
  private final MyEditorPropertyChangeListener myEditorPropertyChangeListener;
  /**
   * Updates tabs names
   */
  private final MyVirtualFileListener myVirtualFileListener;
  /**
   * Extends/cuts number of opened tabs. Also updates location of tabs.
   */
  private final MyUISettingsListener myUISettingsListener;

  /**
   * Updates icons for open files when project roots change
   */
  private final MyPsiTreeChangeListener myPsiTreeChangeListener;

  private final WolfTheProblemSolver.ProblemListener myProblemListener;

  final EditorsSplitters mySplitters;
  private boolean myDoNotTransferFocus = false;

  private List<EditorDataProvider> myDataProviders = new ArrayList<EditorDataProvider>();
  private MessageBusConnection myConnection;


  FileEditorManagerImpl(final Project project) {
/*    ApplicationManager.getApplication().assertIsDispatchThread(); */
    myProject = project;
    myPanels = new JPanel(new BorderLayout());
    mySplitters = new EditorsSplitters(this);
    myPanels.add(mySplitters, BorderLayout.CENTER);

    myFileStatusListener = new MyFileStatusListener();
    myEditorPropertyChangeListener = new MyEditorPropertyChangeListener();
    myVirtualFileListener = new MyVirtualFileListener();
    myUISettingsListener = new MyUISettingsListener();
    myPsiTreeChangeListener = new MyPsiTreeChangeListener();
    myProblemListener = new MyProblemListener();
  }

  //-------------------------------------------------------------------------------

  public JComponent getComponent() {
    return myPanels;
  }

  public JComponent getPreferredFocusedComponent() {
    assertThread();
    final EditorWindow window = mySplitters.getCurrentWindow();
    if (window != null) {
      final EditorWithProviderComposite editor = window.getSelectedEditor();
      if (editor != null) {
        return editor.getPreferredFocusedComponent();
      }
    }
    return null;
  }

  //-------------------------------------------------------

  /**
   * @return color of the <code>file</code> which corresponds to the
   *         file's status
   */
  Color getFileColor(@NotNull final VirtualFile file) {
    final FileStatusManager fileStatusManager = FileStatusManager.getInstance(myProject);
    Color statusColor = fileStatusManager != null ? fileStatusManager.getStatus(file).getColor() : Color.BLACK;
    if (statusColor == null) statusColor = Color.BLACK;
    if (WolfTheProblemSolver.getInstance(getProject()).isProblemFile(file)) {
      return new Color(statusColor.getRed(), statusColor.getGreen(), statusColor.getBlue(), WaverGraphicsDecorator.WAVE_ALPHA_KEY);
    }
    return statusColor;
  }


  /**
   * Updates tab color for the specified <code>file</code>. The <code>file</code>
   * should be opened in the myEditor, otherwise the method throws an assertion.
   */
  private void updateFileColor(final VirtualFile file) {
    mySplitters.updateFileColor(file);
  }


  /**
   * Updates tab icon for the specified <code>file</code>. The <code>file</code>
   * should be opened in the myEditor, otherwise the method throws an assertion.
   */
  private void updateFileIcon(final VirtualFile file) {
    mySplitters.updateFileIcon(file);
  }

  /**
   * Updates tab title and tab tool tip for the specified <code>file</code>
   */
  void updateFileName(@Nullable final VirtualFile file) {
    // Queue here is to prevent title flickering when tab is being closed and two events arriving: with component==null and component==next focused tab
    // only the last event makes sense to handle
    myQueue.queue(new Update("UpdateFileName "+(file==null?"":file.getPath())) {
      public boolean isExpired() {
        if (myProject.isDisposed() || !myProject.isOpen()) return true;
        return file == null ? super.isExpired() : !file.isValid();
      }

      public void run() {
        final WindowManagerEx windowManagerEx = WindowManagerEx.getInstanceEx();
        final IdeFrameImpl frame = windowManagerEx.getFrame(myProject);
        LOG.assertTrue(frame != null);
        mySplitters.updateFileName(file);
        frame.setFileTitle(file);
      }
    });
  }

  //-------------------------------------------------------


  public VirtualFile getFile(@NotNull final FileEditor editor) {
    final EditorComposite editorComposite = getEditorComposite(editor);
    if (editorComposite != null) {
      return editorComposite.getFile();
    }
    return null;
  }

  public void unsplitWindow() {
    final EditorWindow currentWindow = mySplitters.getCurrentWindow();
    if (currentWindow != null) {
      currentWindow.unsplit();
    }
  }

  public void unsplitAllWindow() {
    final EditorWindow currentWindow = mySplitters.getCurrentWindow();
    if (currentWindow != null) {
      currentWindow.unsplitAll();
    }
  }

  @NotNull
  public EditorWindow [] getWindows() {
    return mySplitters.getWindows();
  }

  public EditorWindow getNextWindow(@NotNull final EditorWindow window) {
    final EditorWindow[] windows = mySplitters.getOrderedWindows();
    for (int i = 0; i != windows.length; ++i) {
      if (windows[i].equals(window)) {
        return windows[(i + 1) % windows.length];
      }
    }
    LOG.error("Not window found");
    return null;
  }

  public EditorWindow getPrevWindow(@NotNull final EditorWindow window) {
    final EditorWindow[] windows = mySplitters.getOrderedWindows();
    for (int i = 0; i != windows.length; ++i) {
      if (windows[i].equals(window)) {
        return windows[(i + windows.length - 1) % windows.length];
      }
    }
    LOG.error("Not window found");
    return null;
  }

  public void createSplitter(final int orientation) {
    final EditorWindow currentWindow = mySplitters.getCurrentWindow();
    if (currentWindow != null) {
      currentWindow.split(orientation);
    }
  }

  public void changeSplitterOrientation() {
    final EditorWindow currentWindow = mySplitters.getCurrentWindow();
    if (currentWindow != null) {
      currentWindow.changeOrientation();
    }
  }


  public void flipTabs() {
    /*
    if (myTabs == null) {
      myTabs = new EditorTabs (this, UISettings.getInstance().EDITOR_TAB_PLACEMENT);
      remove (mySplitters);
      add (myTabs, BorderLayout.CENTER);
      initTabs ();
    } else {
      remove (myTabs);
      add (mySplitters, BorderLayout.CENTER);
      myTabs.dispose ();
      myTabs = null;
    }
    */
    myPanels.revalidate();
  }

  public boolean tabsMode() {
    return false;
  }

  private void setTabsMode(final boolean mode) {
    if (tabsMode() != mode) {
      flipTabs();
    }
    //LOG.assertTrue (tabsMode () == mode);
  }


  public boolean isInSplitter() {
    final EditorWindow currentWindow = mySplitters.getCurrentWindow();
    return currentWindow != null && currentWindow.inSplitter();
  }

  public boolean hasOpenedFile() {
    final EditorWindow currentWindow = mySplitters.getCurrentWindow();
    return currentWindow != null && currentWindow.getSelectedEditor() != null;
  }

  public VirtualFile getCurrentFile() {
    return mySplitters.getCurrentFile();
  }

  public EditorWindow getCurrentWindow() {
    return mySplitters.getCurrentWindow();
  }

  public void setCurrentWindow(final EditorWindow window) {
    mySplitters.setCurrentWindow(window, true);
  }

  public void closeFile(@NotNull final VirtualFile file, @NotNull final EditorWindow window) {
    assertThread();

    CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
      public void run() {
        if (window.isFileOpen(file)) {
          window.closeFile(file);
          final List<EditorWindow> windows = mySplitters.findWindows(file);
          if (windows.isEmpty()) { // no more windows containing this file left
            final LocalFileSystem.WatchRequest request = file.getUserData(WATCH_REQUEST_KEY);
            if (request != null) {
              LocalFileSystem.getInstance().removeWatchedRoot(request);
            }
          }
        }
      }
    }, IdeBundle.message("command.close.active.editor"), null);
  }

  //============================= EditorManager methods ================================
  public void closeFile(@NotNull final VirtualFile file) {
    assertThread();

    final LocalFileSystem.WatchRequest request = file.getUserData(WATCH_REQUEST_KEY);
    if (request != null) {
      LocalFileSystem.getInstance().removeWatchedRoot(request);
    }

    CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
      public void run() {
        closeFileImpl(file);
      }
    }, "", null);
  }


  private VirtualFile findNextFile(final VirtualFile file) {
    final EditorWindow [] windows = getWindows(); // TODO: use current file as base
    for (int i = 0; i != windows.length; ++ i) {
      final VirtualFile[] files = windows[i].getFiles();
      for (final VirtualFile fileAt : files) {
        if (fileAt != file) {
          return fileAt;
        }
      }
    }
    return null;
  }

  private void closeFileImpl(@NotNull final VirtualFile file) {
    assertThread();
    ++mySplitters.myInsideChange;
    try {
      final List<EditorWindow> windows = mySplitters.findWindows(file);
      if (!windows.isEmpty()) {
        final VirtualFile nextFile = findNextFile(file);
        for (final EditorWindow window : windows) {
          LOG.assertTrue(window.getSelectedEditor() != null);
          window.closeFile(file, false);
          if (window.getTabCount() == 0 && nextFile != null) {
            EditorWithProviderComposite newComposite = newEditorComposite(nextFile, null);
            window.setEditor(newComposite, true); // newComposite can be null
          }
        }
        // cleanup windows with no tabs
        for (final EditorWindow window : windows) {
          if (window.isDisposed()) {
            // call to window.unsplit() which might make its sibling disposed
            continue;
          }
          if (window.getTabCount() == 0) {
            window.unsplit();
          }
        }
      }
    }
    finally {
      --mySplitters.myInsideChange;
    }
  }

//-------------------------------------- Open File ----------------------------------------

  @NotNull public Pair<FileEditor[], FileEditorProvider[]> openFileWithProviders(@NotNull final VirtualFile file, final boolean focusEditor) {
    if (!file.isValid()) {
      throw new IllegalArgumentException("file is not valid: " + file);
    }
    assertThread();
    return openFileImpl(file, focusEditor, null);
  }

  @NotNull private Pair<FileEditor[], FileEditorProvider[]> openFileImpl(final VirtualFile file, final boolean focusEditor, final HistoryEntry entry) {
    return openFileImpl2(mySplitters.getOrCreateCurrentWindow(file), file, focusEditor, entry);
  }

  @NotNull Pair<FileEditor[], FileEditorProvider[]> openFileImpl2(final EditorWindow window, final VirtualFile file, final boolean focusEditor,
                                                                  final HistoryEntry entry) {
    final Ref<Pair<FileEditor[], FileEditorProvider[]>> resHolder = new Ref<Pair<FileEditor[], FileEditorProvider[]>>();
    CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
      public void run() {
        resHolder.set(openFileImpl3(window, file, focusEditor, entry));
      }
    }, "", null);
    return resHolder.get();
  }

  /**
   * @param file  to be opened. Unlike openFile method, file can be
   *              invalid. For example, all file were invalidate and they are being
   *              removed one by one. If we have removed one invalid file, then another
   *              invalid file become selected. That's why we do not require that
   *              passed file is valid.
   * @param entry map between FileEditorProvider and FileEditorState. If this parameter
   *              is not <code>null</code> then it's used to restore state for the newly created
   */
  @NotNull Pair<FileEditor[], FileEditorProvider[]> openFileImpl3(final EditorWindow window, final VirtualFile file, final boolean focusEditor,
                                                                  final HistoryEntry entry) {
    LOG.assertTrue(file != null);

    // Open file
    window.myInsideTabChange++;
    FileEditor[] editors;
    FileEditorProvider[] providers;
    try {
      final EditorWithProviderComposite newSelectedComposite;
      boolean newEditorCreated = false;

      final boolean open = window.isFileOpen(file);
      if (open) {
        // File is already opened. In this case we have to just select existing EditorComposite
        newSelectedComposite = window.findFileComposite(file);
        LOG.assertTrue(newSelectedComposite != null);

        editors = newSelectedComposite.getEditors();
        providers = newSelectedComposite.getProviders();
      }
      else {
        // File is not opened yet. In this case we have to create editors
        // and select the created EditorComposite.
        final FileEditorProviderManager editorProviderManager = FileEditorProviderManager.getInstance();
        providers = editorProviderManager.getProviders(myProject, file);

        if (providers.length == 0) {
          return Pair.create(EMPTY_EDITOR_ARRAY, EMPTY_PROVIDER_ARRAY);
        }
        newEditorCreated = true;

        editors = new FileEditor[providers.length];
        for (int i = 0; i < providers.length; i++) {
          try {
            final FileEditorProvider provider = providers[i];
            LOG.assertTrue(provider != null);
            LOG.assertTrue(provider.accept(myProject, file));
            final FileEditor editor = provider.createEditor(myProject, file);
            editors[i] = editor;
            LOG.assertTrue(editor != null);
            LOG.assertTrue(editor.isValid());

            // Register PropertyChangeListener into editor
            editor.addPropertyChangeListener(myEditorPropertyChangeListener);
          }
          catch (Exception e) {
            LOG.error(e);
          }
          catch (AssertionError e) {
            LOG.error(e);
          }
        }

        // Now we have to create EditorComposite and insert it into the TabbedEditorComponent.
        // After that we have to select opened editor.
        newSelectedComposite = new EditorWithProviderComposite(file, editors, providers, this);
      }

      window.setEditor(newSelectedComposite, focusEditor);

      final EditorHistoryManager editorHistoryManager = EditorHistoryManager.getInstance(myProject);
      for (int i = 0; i < editors.length; i++) {
        final FileEditor editor = editors[i];
        if (editor instanceof TextEditor) {
          // hack!!!
          // This code prevents "jumping" on next repaint.
          ((EditorEx)((TextEditor)editor).getEditor()).stopOptimizedScrolling();
        }

        final FileEditorProvider provider = providers[i];//getProvider(editor);

        // Restore editor state
        FileEditorState state = null;
        if (entry != null) {
          state = entry.getState(provider);
        }
        if (state == null && !open) {
          // We have to try to get state from the history only in case
          // if editor is not opened. Otherwise history enty might have a state
          // out of sync with the current editor state.
          state = editorHistoryManager.getState(file, provider);
        }
        if (state != null) {
          editor.setState(state);
        }
      }

      // Restore selected editor
      final FileEditorProvider selectedProvider = editorHistoryManager.getSelectedProvider(file);
      if (selectedProvider != null) {
        final FileEditor[] _editors = newSelectedComposite.getEditors();
        final FileEditorProvider[] _providers = newSelectedComposite.getProviders();
        for (int i = _editors.length - 1; i >= 0; i--) {
          final FileEditorProvider provider = _providers[i];//getProvider(_editors[i]);
          if (provider.equals(selectedProvider)) {
            newSelectedComposite.setSelectedEditor(i);
            break;
          }
        }
      }

      // Notify editors about selection changes
      mySplitters.setCurrentWindow(window, false);
      newSelectedComposite.getSelectedEditor().selectNotify();

      if (newEditorCreated) {
        getProject().getMessageBus().syncPublisher(ProjectTopics.FILE_EDITOR_MANAGER).fileOpened(this, file);

        //Add request to watch this editor's virtual file
        final VirtualFile parentDir = file.getParent();
        if (parentDir != null) {
          final LocalFileSystem.WatchRequest request = LocalFileSystem.getInstance().addRootToWatch(parentDir.getPath(), false);
          file.putUserData(WATCH_REQUEST_KEY, request);
        }
      }

      //[jeka] this is a hack to support back-forward navigation
      // previously here was incorrect call to fireSelectionChanged() with a side-effect
      ((IdeDocumentHistoryImpl)IdeDocumentHistory.getInstance(myProject)).onSelectionChanged();

      // Transfer focus into editor
      if (!ApplicationManagerEx.getApplicationEx().isUnitTestMode()) {
        if ((focusEditor || ToolWindowManager.getInstance(myProject).isEditorComponentActive()) &&
            !myDoNotTransferFocus) {
          //myFirstIsActive = myTabbedContainer1.equals(tabbedContainer);
          window.setAsCurrentWindow(false);
          ToolWindowManager.getInstance(myProject).activateEditorComponent();
        }
      }

      // Update frame and tab title
      updateFileName(file);

      // Make back/forward work
      IdeDocumentHistory.getInstance(myProject).includeCurrentCommandAsNavigation();
    }
    finally {
      window.myInsideTabChange--;
    }

    return Pair.create(editors, providers);
  }

  private void setSelectedEditor(VirtualFile file, String fileEditorProviderId) {
    EditorWithProviderComposite composite = getCurrentEditorWithProviderComposite(file);
    if (composite == null) {
      final List<EditorWithProviderComposite> composites = getEditorComposites(file);

      if (composites.isEmpty()) return;
      composite = composites.get(0);
    }

    final FileEditorProvider[] editorProviders = composite.getProviders();
    final FileEditorProvider selectedProvider = composite.getSelectedEditorWithProvider().getSecond();

    for (int i = 0; i < editorProviders.length; i++) {
      if (editorProviders[i].getEditorTypeId().equals(fileEditorProviderId) &&  !selectedProvider.equals(editorProviders[i])) {
        composite.setSelectedEditor(i);
        composite.getSelectedEditor().selectNotify();
      }
    }
  }


  private EditorWithProviderComposite newEditorComposite(final VirtualFile file, final HistoryEntry entry) {
    if (file == null) {
      return null;
    }

    final FileEditorProviderManager editorProviderManager = FileEditorProviderManager.getInstance();
    final FileEditorProvider[] providers = editorProviderManager.getProviders(myProject, file);
    final FileEditor[] editors = new FileEditor[providers.length];
    for (int i = 0; i < providers.length; i++) {
      final FileEditorProvider provider = providers[i];
      LOG.assertTrue(provider != null);
      LOG.assertTrue(provider.accept(myProject, file));
      final FileEditor editor = provider.createEditor(myProject, file);
      editors[i] = editor;
      LOG.assertTrue(editor.isValid());
      editor.addPropertyChangeListener(myEditorPropertyChangeListener);
    }

    final EditorWithProviderComposite newComposite = new EditorWithProviderComposite(file, editors, providers, this);
    final EditorHistoryManager editorHistoryManager = EditorHistoryManager.getInstance(myProject);
    for (int i = 0; i < editors.length; i++) {
      final FileEditor editor = editors[i];
      if (editor instanceof TextEditor) {
        // hack!!!
        // This code prevents "jumping" on next repaint.
        //((EditorEx)((TextEditor)editor).getEditor()).stopOptimizedScrolling();
      }

      final FileEditorProvider provider = providers[i];

// Restore myEditor state
      FileEditorState state = null;
      if (entry != null) {
        state = entry.getState(provider);
      }
      if (state == null/* && !open*/) {
        // We have to try to get state from the history only in case
        // if myEditor is not opened. Otherwise history enty might have a state
        // no in sych with the current myEditor state.
        state = editorHistoryManager.getState(file, provider);
      }
      if (state != null) {
        editor.setState(state);
      }
    }
    return newComposite;
  }

  public List<FileEditor> openEditor(@NotNull final OpenFileDescriptor descriptor, final boolean focusEditor, final String fileEditorProviderId) {
    final List<FileEditor> list = openEditor(descriptor, focusEditor);

    setSelectedEditor(descriptor.getFile(), fileEditorProviderId);

    return list;
  }

  @NotNull
  public List<FileEditor> openEditor(@NotNull final OpenFileDescriptor descriptor, final boolean focusEditor) {
    assertThread();
    if (descriptor.getFile() instanceof VirtualFileDelegate) {
      VirtualFileDelegate delegate = (VirtualFileDelegate)descriptor.getFile();
      int hostOffset = delegate.getDocumentRange().injectedToHost(descriptor.getOffset());
      OpenFileDescriptor realDescriptor = new OpenFileDescriptor(descriptor.getProject(), delegate.getDelegate(), hostOffset);
      return openEditor(realDescriptor, focusEditor);
    }

    final List<FileEditor> result = new ArrayList<FileEditor>();
    CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
      public void run() {
        VirtualFile file = descriptor.getFile();
        final FileEditor[] editors = openFile(file, focusEditor);
        result.addAll(Arrays.asList(editors));

        boolean navigated = false;
        for (final FileEditor editor : editors) {
          if (editor instanceof NavigatableFileEditor && getSelectedEditor(descriptor.getFile()) == editor) { // try to navigate opened editor
            navigated = navigateAndSelectEditor((NavigatableFileEditor) editor,  descriptor);
            if (navigated) break;
          }
        }

        if (!navigated) {
          for (final FileEditor editor : editors) {
            if (editor instanceof NavigatableFileEditor && getSelectedEditor(descriptor.getFile()) != editor) { // try other editors
              if (navigateAndSelectEditor((NavigatableFileEditor) editor, descriptor)) {
                break;
              }
            }
          }
        }
      }
    }, "", null);

    return result;
  }

  private boolean navigateAndSelectEditor(final NavigatableFileEditor editor, final OpenFileDescriptor descriptor) {
    if (editor.canNavigateTo(descriptor)) {
      setSelectedEditor(editor);
      editor.navigateTo(descriptor);
      return true;
    }

    return false;
  }

  private void setSelectedEditor(final FileEditor editor) {
    final EditorWithProviderComposite composite = getEditorComposite(editor);
    final FileEditor[] editors = composite.getEditors();
    for (int i = 0; i < editors.length; i++) {
      final FileEditor each = editors[i];
      if (editor == each) {
        composite.setSelectedEditor(i);
        composite.getSelectedEditor().selectNotify();
        break;
      }
    }
  }

  @NotNull
  public Project getProject() {
    return myProject;
  }

  public void registerExtraEditorDataProvider(@NotNull final EditorDataProvider provider, Disposable parentDisposable) {
    myDataProviders.add(provider);
    Disposer.register(parentDisposable, new Disposable() {
      public void dispose() {
        myDataProviders.remove(provider);
      }
    });
  }

  @Nullable
  public final Object getData(String dataId, Editor editor) {
    for (final EditorDataProvider dataProvider : myDataProviders) {
      final Object o = dataProvider.getData(dataId, editor);
      if (o != null) return o;
    }
    return null;
  }

  @Nullable
  public Editor openTextEditor(final OpenFileDescriptor descriptor, final boolean focusEditor) {
    final Collection<FileEditor> fileEditors = openEditor(descriptor, focusEditor);
    for (FileEditor fileEditor : fileEditors) {
      if (fileEditor instanceof TextEditor) {
        setSelectedEditor(descriptor.getFile(), TextEditorProvider.getInstance().getEditorTypeId());
        Editor editor = ((TextEditor)fileEditor).getEditor();
        PsiFile psiFile = PsiDocumentManager.getInstance(getProject()).getPsiFile(editor.getDocument());
        return focusEditor ? InjectedLanguageUtil.getEditorForInjectedLanguage(editor, psiFile) : editor;
      }
    }

    return null;
  }

  public Editor getSelectedTextEditor() {
    assertThread();

    final EditorWindow currentWindow = mySplitters.getCurrentWindow();
    if (currentWindow != null) {
      final EditorWithProviderComposite selectedEditor = currentWindow.getSelectedEditor();
      if (selectedEditor != null && selectedEditor.getSelectedEditor() instanceof TextEditor) {
        return ((TextEditor)selectedEditor.getSelectedEditor()).getEditor();
      }
    }

    return null;
  }


  public boolean isFileOpen(@NotNull final VirtualFile file) {
    return getEditors(file).length != 0;
  }

  @NotNull
  public VirtualFile[] getOpenFiles() {
    return mySplitters.getOpenFiles();
  }

  @NotNull
  public VirtualFile[] getSelectedFiles() {
    return mySplitters.getSelectedFiles();
  }

  @NotNull
  public FileEditor[] getSelectedEditors() {
    return mySplitters.getSelectedEditors();
  }

  public FileEditor getSelectedEditor(@NotNull final VirtualFile file) {
    final Pair<FileEditor, FileEditorProvider> selectedEditorWithProvider = getSelectedEditorWithProvider(file);
    return selectedEditorWithProvider == null ? null : selectedEditorWithProvider.getFirst();
  }


  public Pair<FileEditor, FileEditorProvider> getSelectedEditorWithProvider(@NotNull final VirtualFile file) {
    final EditorWithProviderComposite composite = getCurrentEditorWithProviderComposite(file);
    if (composite != null) {
      return composite.getSelectedEditorWithProvider();
    }

    final List<EditorWithProviderComposite> composites = getEditorComposites(file);
    if (!composites.isEmpty()) {
      return composites.get(0).getSelectedEditorWithProvider();
    }
    else {
      return null;
    }
  }

  @NotNull
  public Pair<FileEditor[], FileEditorProvider[]> getEditorsWithProviders(@NotNull final VirtualFile file) {
    assertThread();

    final EditorWithProviderComposite composite = getCurrentEditorWithProviderComposite(file);
    if (composite != null) {
      return Pair.create(composite.getEditors(), composite.getProviders());
    }

    final List<EditorWithProviderComposite> composites = getEditorComposites(file);
    if (!composites.isEmpty()) {
      return Pair.create(composites.get(0).getEditors(), composites.get(0).getProviders());
    }
    else {
      return Pair.create(EMPTY_EDITOR_ARRAY, EMPTY_PROVIDER_ARRAY);
    }
  }

  @NotNull
  public FileEditor[] getEditors(@NotNull final VirtualFile file) {
    assertThread();

    final EditorWithProviderComposite composite = getCurrentEditorWithProviderComposite(file);
    if (composite != null) {
      return composite.getEditors();
    }

    final List<EditorWithProviderComposite> composites = getEditorComposites(file);
    if (!composites.isEmpty()) {
      return composites.get(0).getEditors();
    }
    else {
      return EMPTY_EDITOR_ARRAY;
    }
  }

  @Nullable
  private EditorWithProviderComposite getCurrentEditorWithProviderComposite(@NotNull final VirtualFile virtualFile) {
    final EditorWindow editorWindow = mySplitters.getCurrentWindow();
    if (editorWindow != null) {
      return editorWindow.findFileComposite(virtualFile);
    }
    return null;
  }

  @NotNull
  public List<EditorWithProviderComposite> getEditorComposites(final VirtualFile file) {
    return mySplitters.findEditorComposites(file);
  }

  @NotNull
  public FileEditor[] getAllEditors() {
    assertThread();
    final ArrayList<FileEditor> result = new ArrayList<FileEditor>();
    final EditorWithProviderComposite[] editorsComposites = mySplitters.getEditorsComposites();
    for (EditorWithProviderComposite editorsComposite : editorsComposites) {
      final FileEditor[] editors = editorsComposite.getEditors();
      result.addAll(Arrays.asList(editors));
    }
    return result.toArray(new FileEditor[result.size()]);
  }

  public void showEditorAnnotation(@NotNull FileEditor editor, @NotNull JComponent annotationComponent) {
    final EditorComposite composite = getEditorComposite(editor);
    if (composite != null) {
      composite.getPane(editor).addInfo(annotationComponent);
    }
  }

  public void removeEditorAnnotation(@NotNull FileEditor editor, @NotNull JComponent annotationComponent) {
    final EditorComposite composite = getEditorComposite(editor);
    if (composite != null) {
      composite.getPane(editor).removeInfo(annotationComponent);
    }
  }

  public void addTopComponent(@NotNull final FileEditor editor, @NotNull final JComponent component) {
    final EditorComposite composite = getEditorComposite(editor);
    if (composite != null) {
      composite.addTopComponent(editor, component);
    }
  }

  public void removeTopComponent(@NotNull final FileEditor editor, @NotNull final JComponent component) {
    final EditorComposite composite = getEditorComposite(editor);
    if (composite != null) {
      composite.removeTopComponent(editor, component);
    }
  }

  public void addBottomComponent(@NotNull final FileEditor editor, @NotNull final JComponent component) {
    final EditorComposite composite = getEditorComposite(editor);
    if (composite != null) {
      composite.addBottomComponent(editor, component);
    }
  }

  public void removeBottomComponent(@NotNull final FileEditor editor, @NotNull final JComponent component) {
    final EditorComposite composite = getEditorComposite(editor);
    if (composite != null) {
      composite.removeBottomComponent(editor, component);
    }
  }

  private final Map<FileEditorManagerListener, MessageBusConnection> myListenerToConnectionMap = new HashMap<FileEditorManagerListener, MessageBusConnection>();

  public void addFileEditorManagerListener(@NotNull final FileEditorManagerListener listener) {
/*    assertThread();*/
    final MessageBusConnection connection = getProject().getMessageBus().connect();
    connection.subscribe(ProjectTopics.FILE_EDITOR_MANAGER, listener);
    myListenerToConnectionMap.put(listener, connection);
  }

  public void addFileEditorManagerListener(@NotNull final FileEditorManagerListener listener, final Disposable parentDisposable) {
    /*assertThread();*/
    Disposer.register(parentDisposable, new Disposable() {
      public void dispose() {
        myListenerToConnectionMap.remove(listener);
      }
    });
    final MessageBusConnection connection = getProject().getMessageBus().connect(parentDisposable);
    connection.subscribe(ProjectTopics.FILE_EDITOR_MANAGER, listener);
    myListenerToConnectionMap.put(listener, connection);
  }

  public void removeFileEditorManagerListener(@NotNull final FileEditorManagerListener listener) {
    /*assertThread();*/
    final MessageBusConnection connection = myListenerToConnectionMap.remove(listener);
    if (connection != null) {
      connection.disconnect();
    }
  }

// ProjectComponent methods

  public void projectOpened() {
    //myFocusWatcher.install(myWindows.getComponent ());
    mySplitters.startListeningFocus();

    myConnection = myProject.getMessageBus().connect();

    final FileStatusManager fileStatusManager = FileStatusManager.getInstance(myProject);
    if (fileStatusManager != null) {
      fileStatusManager.addFileStatusListener(myFileStatusListener);
    }
    myConnection.subscribe(AppTopics.FILE_TYPES, new MyFileTypeListener());
    VirtualFileManager.getInstance().addVirtualFileListener(myVirtualFileListener);
    UISettings.getInstance().addUISettingsListener(myUISettingsListener);
    PsiManager.getInstance(myProject).addPsiTreeChangeListener(myPsiTreeChangeListener);
    WolfTheProblemSolver.getInstance(getProject()).addProblemListener(myProblemListener);

    StartupManager.getInstance(myProject).registerPostStartupActivity(new Runnable() {
      public void run() {
        ToolWindowManager.getInstance(myProject).invokeLater(new Runnable() {
          public void run() {
            CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
              public void run() {
                setTabsMode(UISettings.getInstance().EDITOR_TAB_PLACEMENT != UISettings.TABS_NONE);
                mySplitters.openFiles();
// group 1
              }
            }, "", null);
          }
        });
      }
    });

  }

  public void projectClosed() {
    //myFocusWatcher.deinstall(myWindows.getComponent ());
    mySplitters.dispose();

    myConnection.disconnect();

// Remove application level listeners
    final FileStatusManager fileStatusManager = FileStatusManager.getInstance(myProject);
    if (fileStatusManager != null) {
      fileStatusManager.removeFileStatusListener(myFileStatusListener);
    }
    VirtualFileManager.getInstance().removeVirtualFileListener(myVirtualFileListener);
    UISettings.getInstance().removeUISettingsListener(myUISettingsListener);

// Dispose created editors. We do not use use closeEditor method because
// it fires event and changes history.
    closeAllFiles();
  }

// BaseCompomemnt methods

  @NotNull
  public String getComponentName() {
    return "FileEditorManager";
  }

  public void initComponent() { /* really do nothing */ }

  public void disposeComponent() { /* really do nothing */  }

//JDOMExternalizable methods

  public void writeExternal(final Element element) {
    mySplitters.writeExternal(element);
  }

  public void readExternal(final Element element) {
    mySplitters.readExternal(element);
  }

  private EditorWithProviderComposite getEditorComposite(final FileEditor editor) {
    LOG.assertTrue(editor != null);
    final EditorWithProviderComposite[] editorsComposites = mySplitters.getEditorsComposites();
    for (int i = editorsComposites.length - 1; i >= 0; i--) {
      final EditorWithProviderComposite composite = editorsComposites[i];
      final FileEditor[] editors = composite.getEditors();
      for (int j = editors.length - 1; j >= 0; j--) {
        final FileEditor _editor = editors[j];
        LOG.assertTrue(_editor != null);
        if (editor.equals(_editor)) {
          return composite;
        }
      }
    }
    return null;
  }

//======================= Misc =====================

  public static void assertThread() {
    ApplicationManager.getApplication().assertIsDispatchThread();
  }

  public void fireSelectionChanged(final EditorComposite oldSelectedComposite, final EditorComposite newSelectedComposite) {
    final VirtualFile oldSelectedFile = oldSelectedComposite != null ? oldSelectedComposite.getFile() : null;
    final VirtualFile newSelectedFile = newSelectedComposite != null ? newSelectedComposite.getFile() : null;
    final FileEditor oldSelectedEditor = oldSelectedComposite != null ? oldSelectedComposite.getSelectedEditor() : null;
    final FileEditor newSelectedEditor = newSelectedComposite != null ? newSelectedComposite.getSelectedEditor() : null;
    final boolean filesEqual = oldSelectedFile == null ? newSelectedFile == null : oldSelectedFile.equals(newSelectedFile);
    final boolean editorsEqual = oldSelectedEditor == null ? newSelectedEditor == null : oldSelectedEditor.equals(newSelectedEditor);
    if (!filesEqual || !editorsEqual) {
      final FileEditorManagerEvent event =
        new FileEditorManagerEvent(this, oldSelectedFile, oldSelectedEditor, newSelectedFile, newSelectedEditor);
      final FileEditorManagerListener publisher = getProject().getMessageBus().syncPublisher(ProjectTopics.FILE_EDITOR_MANAGER);
      publisher.selectionChanged(event);
    }
  }

  public boolean isChanged(@NotNull final EditorComposite editor) {
    final FileStatusManager fileStatusManager = FileStatusManager.getInstance(myProject);
    if (fileStatusManager != null) {
      if (!fileStatusManager.getStatus(editor.getFile()).equals(FileStatus.NOT_CHANGED)) {
        return true;
      }
    }
    return false;
  }

  public void disposeComposite(EditorWithProviderComposite editor) {
    if (editor.equals(getLastSelected())) {
      editor.getSelectedEditor().deselectNotify();
      mySplitters.setCurrentWindow(null, false);
    }

    final FileEditor[] editors = editor.getEditors();
    final FileEditorProvider[] providers = editor.getProviders();

    final FileEditor selectedEditor = editor.getSelectedEditor();
    for (int i = editors.length - 1; i >= 0; i--) {
      final FileEditor editor1 = editors[i];
      final FileEditorProvider provider = providers[i];
      if (!editor.equals(selectedEditor)) { // we already notified the myEditor (when fire event)
        if (selectedEditor.equals(editor1)) {
          editor1.deselectNotify();
        }
      }
      editor1.removePropertyChangeListener(myEditorPropertyChangeListener);
      provider.disposeEditor(editor1);
    }
  }

  EditorComposite getLastSelected() {
    final EditorWindow currentWindow = mySplitters.getCurrentWindow();
    if (currentWindow != null) {
      return currentWindow.getSelectedEditor();
    }
    return null;
  }

  public boolean isFocusingBlocked() {
    return myDoNotTransferFocus;
  }

  //================== Listeners =====================

  /**
   * Closes deleted files. Closes file which are in the deleted directories.
   */
  private final class MyVirtualFileListener extends VirtualFileAdapter {
    public void beforeFileDeletion(VirtualFileEvent e) {
      assertThread();
      final VirtualFile file = e.getFile();
      final VirtualFile[] openFiles = getOpenFiles();
      for (int i = openFiles.length - 1; i >= 0; i--) {
        if (VfsUtil.isAncestor(file, openFiles[i], false)) {
          closeFile(openFiles[i]);
        }
      }
    }

    public void propertyChanged(VirtualFilePropertyEvent e) {
      if (VirtualFile.PROP_NAME.equals(e.getPropertyName())) {
        assertThread();
        final VirtualFile file = e.getFile();
        if (isFileOpen(file)) {
          updateFileName(file);
          updateFileIcon(file); // file type can change after renaming
        }
      }
      else if (VirtualFile.PROP_WRITABLE.equals(e.getPropertyName())) {
        assertThread();
        final VirtualFile file = e.getFile();
        if (isFileOpen(file)) {
          updateFileIcon(file);
          if (file.equals(getSelectedFiles()[0])) { // update "write" status
            final StatusBarEx statusBar = (StatusBarEx)WindowManager.getInstance().getStatusBar(myProject);
            LOG.assertTrue(statusBar != null);
            statusBar.setWriteStatus(!file.isWritable());
          }
        }
      }
    }

    public void fileMoved(VirtualFileMoveEvent e) {
      final VirtualFile file = e.getFile();
      final VirtualFile[] selectedFiles = getSelectedFiles();
      for (final VirtualFile selectedFile : selectedFiles) {
        if (VfsUtil.isAncestor(file, selectedFile, false)) {
          updateFileName(selectedFile);
        }
      }
    }
  }

/*
private final class MyVirtualFileListener extends VirtualFileAdapter {
  public void beforeFileDeletion(final VirtualFileEvent e) {
    assertThread();
    final VirtualFile file = e.getFile();
    final VirtualFile[] openFiles = getOpenFiles();
    for (int i = openFiles.length - 1; i >= 0; i--) {
      if (VfsUtil.isAncestor(file, openFiles[i], false)) {
        closeFile(openFiles[i]);
      }
    }
  }

  public void propertyChanged(final VirtualFilePropertyEvent e) {
    if (VirtualFile.PROP_WRITABLE.equals(e.getPropertyName())) {
      assertThread();
      final VirtualFile file = e.getFile();
      if (isFileOpen(file)) {
        if (file.equals(getSelectedFiles()[0])) { // update "write" status
          final StatusBarEx statusBar = (StatusBarEx)WindowManager.getInstance().getStatusBar(myProject);
          LOG.assertTrue(statusBar != null);
          statusBar.setWriteStatus(!file.isWritable());
        }
      }
    }
  }

  //public void fileMoved(final VirtualFileMoveEvent e){ }
}
*/

  public boolean isInsideChange() {
    return mySplitters.myInsideChange > 0;
  }

  private final class MyEditorPropertyChangeListener implements PropertyChangeListener {
    public void propertyChange(final PropertyChangeEvent e) {
      assertThread();

      final String propertyName = e.getPropertyName();
      if (FileEditor.PROP_MODIFIED.equals(propertyName)) {
        final FileEditor editor = (FileEditor)e.getSource();
        final EditorComposite composite = getEditorComposite(editor);
        if (composite != null) {
          updateFileIcon(composite.getFile());
        }
      }
      else if (FileEditor.PROP_VALID.equals(propertyName)) {
        final boolean valid = ((Boolean)e.getNewValue()).booleanValue();
        if (!valid) {
          final FileEditor editor = (FileEditor)e.getSource();
          LOG.assertTrue(editor != null);
          final EditorComposite composite = getEditorComposite(editor);
          LOG.assertTrue(composite != null);
          closeFile(composite.getFile());
        }
      }

    }
  }

  /**
   * Gets events from VCS and updates color of myEditor tabs
   */
  private final class MyFileStatusListener implements FileStatusListener {
    public void fileStatusesChanged() { // update color of all open files
      assertThread();
      final VirtualFile[] openFiles = getOpenFiles();
      for (int i = openFiles.length - 1; i >= 0; i--) {
        final VirtualFile file = openFiles[i];
        LOG.assertTrue(file != null);
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          public void run() {
            updateFileStatus(file);
          }
        }, ModalityState.NON_MODAL);
      }
    }

    public void fileStatusChanged(@NotNull final VirtualFile file) { // update color of the file (if necessary)
      assertThread();
      if (isFileOpen(file)) {
        updateFileStatus(file);
      }
    }

    private void updateFileStatus(final VirtualFile file) {
      updateFileColor(file);
      updateFileIcon(file);
    }
  }

  /**
   * Gets events from FileTypeManager and updates icons on tabs
   */
  private final class MyFileTypeListener implements FileTypeListener {
    public void beforeFileTypesChanged(FileTypeEvent event) {
    }

    public void fileTypesChanged(final FileTypeEvent event) {
      assertThread();
      final VirtualFile[] openFiles = getOpenFiles();
      for (int i = openFiles.length - 1; i >= 0; i--) {
        final VirtualFile file = openFiles[i];
        LOG.assertTrue(file != null);
        updateFileIcon(file);
      }
    }
  }

  /**
   * Gets notifications from UISetting component to track changes of RECENT_FILES_LIMIT
   * and EDITOR_TAB_LIMIT, etc values.
   */
  private final class MyUISettingsListener implements UISettingsListener {
    public void uiSettingsChanged(final UISettings source) {
      assertThread();
      setTabsMode(source.EDITOR_TAB_PLACEMENT != UISettings.TABS_NONE);
      mySplitters.setTabsPlacement(source.EDITOR_TAB_PLACEMENT);
      mySplitters.trimToSize(source.EDITOR_TAB_LIMIT);

      // Tab layout policy
      if (source.SCROLL_TAB_LAYOUT_IN_EDITOR) {
        mySplitters.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
      }
      else {
        mySplitters.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
      }

      // "Mark modified files with asterisk"
      final VirtualFile[] openFiles = getOpenFiles();
      for (int i = openFiles.length - 1; i >= 0; i--) {
        final VirtualFile file = openFiles[i];
        updateFileIcon(file);
        updateFileName(file);
      }
    }
  }

  /**
   * Updates attribute of open files when roots change
   */
  private final class MyPsiTreeChangeListener extends PsiTreeChangeAdapter {
    public void propertyChanged(final PsiTreeChangeEvent e) {
      if (PsiTreeChangeEvent.PROP_ROOTS.equals(e.getPropertyName())) {
        assertThread();
        final VirtualFile[] openFiles = getOpenFiles();
        for (int i = openFiles.length - 1; i >= 0; i--) {
          final VirtualFile file = openFiles[i];
          LOG.assertTrue(file != null);
          updateFileIcon(file);
        }
      }
    }

    public void childAdded(PsiTreeChangeEvent event) {
      doChange(event);
    }

    public void childRemoved(PsiTreeChangeEvent event) {
      doChange(event);
    }

    public void childReplaced(PsiTreeChangeEvent event) {
      doChange(event);
    }

    public void childMoved(PsiTreeChangeEvent event) {
      doChange(event);
    }

    public void childrenChanged(PsiTreeChangeEvent event) {
      doChange(event);
    }

    private void doChange(final PsiTreeChangeEvent event) {
      final PsiFile psiFile = event.getFile();
      final VirtualFile currentFile = getCurrentFile();
      if (currentFile != null && psiFile != null && psiFile.getVirtualFile() == currentFile) {
        updateFileIcon(currentFile);
      }
    }
  }

  public void closeAllFiles() {
    final VirtualFile[] openFiles = mySplitters.getOpenFiles();
    for (VirtualFile openFile : openFiles) {
      closeFile(openFile);
    }
  }

  public Editor openTextEditorEnsureNoFocus(@NotNull OpenFileDescriptor descriptor) {
    myDoNotTransferFocus = true;
    try {
      return openTextEditor(descriptor, false);
    }
    finally {
      myDoNotTransferFocus = false;
    }
  }

  @NotNull
  public VirtualFile[] getSiblings(VirtualFile file) {
    return getOpenFiles();
  }

  private class MyProblemListener extends WolfTheProblemSolver.ProblemListener {

    public void problemsAppeared(final VirtualFile file) {
      updateFile(file);
    }

    public void problemsDisappeared(VirtualFile file) {
      updateFile(file);
    }

    public void problemsChanged(VirtualFile file) {
      updateFile(file);
    }

    private void updateFile(final VirtualFile file) {
      myQueue.queue(new Update(file) {
        public void run() {
          if (isFileOpen(file)) {
            updateFileIcon(file);
            updateFileColor(file);
          }
        }
      });
    }

  }
}
