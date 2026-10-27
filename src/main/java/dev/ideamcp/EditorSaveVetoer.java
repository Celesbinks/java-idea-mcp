package dev.ideamcp;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentSynchronizationVetoer;

/** Limits a provider's save-all call to documents changed by that editor operation. */
public final class EditorSaveVetoer extends FileDocumentSynchronizationVetoer {
    @Override public boolean maySaveDocument(Document document, boolean explicit) {
        return EditorWriteBoundary.maySave(document);
    }
}
