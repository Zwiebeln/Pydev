/**
 * Copyright (c) 2005-2012 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Eclipse Public License (EPL).
 * Please see the license.txt included with this distribution for details.
 * Any modifications to this file must keep this entire header intact.
 */
/*
 * Created on 16/09/2005
 */
package com.python.pydev.analysis;

import java.util.List;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;
import org.python.pydev.core.docutils.ImportHandle;
import org.python.pydev.core.docutils.ImportHandle.ImportHandleInfo;
import org.python.pydev.core.docutils.ImportNotRecognizedException;
import org.python.pydev.core.docutils.ParsingUtils;
import org.python.pydev.core.docutils.PyDocIterator;
import org.python.pydev.core.docutils.PyImportsHandling;
import org.python.pydev.core.docutils.PySelection;
import org.python.pydev.core.docutils.PySelection.LineStartingScope;
import org.python.pydev.core.log.Log;
import org.python.pydev.editor.PyEdit;
import org.python.pydev.editor.actions.PyAction;
import org.python.pydev.editor.autoedit.DefaultIndentPrefs;
import org.python.pydev.editor.codecompletion.AbstractPyCompletionProposalExtension2;
import org.python.pydev.editor.codecompletion.IPyCompletionProposal2;
import org.python.pydev.editor.codecompletion.PyCodeCompletionPreferencesPage;
import org.python.pydev.editor.codefolding.PySourceViewer;
import org.python.pydev.plugin.preferences.PydevPrefs;
import org.python.pydev.shared_core.SharedCorePlugin;
import org.python.pydev.shared_core.string.StringUtils;
import org.python.pydev.shared_core.structure.Tuple;
import org.python.pydev.ui.importsconf.ImportsPreferencesPage;

/**
 * This is the proposal that should be used to do a completion that can have a related import.
 *
 * @author Fabio
 */
public class CtxInsensitiveImportComplProposal extends AbstractPyCompletionProposalExtension2 implements
        ICompletionProposalExtension, IPyCompletionProposal2 {

    public static boolean addApplyTipOnAdditionalInfo = true;

    /**
     * If empty, act as a regular completion
     */
    public String realImportRep;

    /**
     * This is the indentation string that should be used
     */
    public String indentString;

    /**
     * Determines if the import was added or if only the completion was applied.
     */
    private int importLen = 0;

    /**
     * Offset forced to be returned (only valid if >= 0)
     */
    private int newForcedOffset = -1;

    /**
     * Indicates if the completion was applied with a trigger char that should be considered
     * (meaning that the resulting position should be summed with 1)
     */
    private boolean appliedWithTrigger = false;

    /**
     * If the import should be added locally or globally.
     */
    private boolean addLocalImport = false;

    /**
     * Can be used to override the preferences in tests.
     */
    public Boolean addLocalImportsOnTopOfFunc = null;

    private int infoTypeForImage;

    public int getInfoTypeForImage() {
        // See IInfo constants.
        return infoTypeForImage;
    }

    public boolean getAddLocalImportsOnTopOfMethod() {
        if (SharedCorePlugin.inTestMode()) {
            if (addLocalImportsOnTopOfFunc != null) {
                return addLocalImportsOnTopOfFunc;
            }
            // In tests the default is true.
            return true;
        }
        return PyCodeCompletionPreferencesPage.getPutLocalImportsOnTopOfMethod();
    }

    public CtxInsensitiveImportComplProposal(String replacementString, int replacementOffset, int replacementLength,
            int cursorPosition, int infoTypeForImage, String displayString, IContextInformation contextInformation,
            String additionalProposalInfo, int priority, String realImportRep, ICompareContext compareContext) {

        super(replacementString, replacementOffset, replacementLength, cursorPosition, null, displayString,
                contextInformation, additionalProposalInfo, priority, ON_APPLY_DEFAULT, "", compareContext);
        this.infoTypeForImage = infoTypeForImage;
        this.realImportRep = realImportRep;
    }

    @Override
    public Image getImage() {
        if (fImage == null) {
            fImage = AnalysisPlugin.getImageForAutoImportTypeInfo(infoTypeForImage);
        }
        return fImage;
    }

    public void setAddLocalImport(boolean b) {
        this.addLocalImport = b;
    }

    public boolean getMakeLocalWhenShiftApplied() {
        return true;
    }

    private static final String MSG = ""
            + "Enter: apply completion.\n"
            + "  + Ctrl: replace current word (no Pop-up focus).\n"
            + "  + Shift: do local import (requires Pop-up focus).\n";

    @Override
    public String getAdditionalProposalInfo() {
        String original = super.getAdditionalProposalInfo();
        if (addApplyTipOnAdditionalInfo && getMakeLocalWhenShiftApplied()) {
            if (original == null || original.length() == 0) {
                return MSG;
            } else {
                return original + "\n\n" + MSG;
            }
        }
        return original;
    }

    /**
     * This is the apply that should actually be called!
     */
    @Override
    public void apply(ITextViewer viewer, char trigger, int stateMask, int offset) {
        IDocument document = viewer.getDocument();
        IAdaptable projectAdaptable;
        if (viewer instanceof PySourceViewer) {
            PySourceViewer pySourceViewer = (PySourceViewer) viewer;
            PyEdit pyEdit = pySourceViewer.getEdit();
            this.indentString = pyEdit.getIndentPrefs().getIndentationString();
            projectAdaptable = pyEdit;
        } else {
            //happens on compare editor
            this.indentString = new DefaultIndentPrefs(null).getIndentationString();
            projectAdaptable = null;
        }
        //If the completion is applied with shift pressed, do a local import. Note that the user is only actually
        //able to do that if the popup menu is focused (i.e.: request completion and do a tab to focus it, instead
        //of having the focus on the editor and just pressing up/down).
        if ((stateMask & SWT.SHIFT) != 0) {
            this.setAddLocalImport(true);
        }
        apply(document, trigger, stateMask, offset, projectAdaptable);
    }

    /**
     * Note: This apply is not directly called (it's called through
     * {@link CtxInsensitiveImportComplProposal#apply(ITextViewer, char, int, int)})
     *
     * This is the point where the completion is written. It has to be written and if some import is also available
     * it should be inserted at this point.
     *
     * We have to be careful to only add an import if that's really needed (e.g.: there's no other import that
     * equals the import that should be added).
     *
     * Also, we have to check if this import should actually be grouped with another import that already exists.
     * (and it could be a multi-line import)
     */
    public void apply(IDocument document, char trigger, int stateMask, int offset) {
        apply(document, trigger, stateMask, offset, null);
    }

    public void apply(IDocument document, char trigger, int stateMask, int offset, IAdaptable projectAdaptable) {
        if (this.indentString == null) {
            throw new RuntimeException("Indent string not set (not called with a PyEdit as viewer?)");
        }

        if (!triggerCharAppliesCurrentCompletion(trigger, document, offset)) {
            newForcedOffset = offset + 1; //+1 because that's the len of the trigger
            return;
        }

        try {
            PySelection selection = new PySelection(document);
            int lineToAddImport = -1;
            ImportHandleInfo groupInto = null;
            ImportHandleInfo realImportHandleInfo = null;

            boolean groupImports = ImportsPreferencesPage.getGroupImports(projectAdaptable);

            LineStartingScope previousLineThatStartsScope = null;
            PySelection ps = null;
            if (this.addLocalImport) {
                ps = new PySelection(document, offset);
                int startLineIndex = ps.getStartLineIndex();
                if (startLineIndex == 0) {
                    this.addLocalImport = false;
                } else {
                    String[] indentTokens = PySelection.INDENT_TOKENS;
                    if (getAddLocalImportsOnTopOfMethod()) {
                        indentTokens = PySelection.FUNC_TOKEN;
                    }
                    previousLineThatStartsScope = ps.getPreviousLineThatStartsScope(indentTokens,
                            startLineIndex - 1, PySelection.getFirstCharPosition(ps.getCursorLineContents()));
                    if (previousLineThatStartsScope == null) {
                        //note that if we have no previous scope, it means we're actually on the global scope, so,
                        //proceed as usual...
                        this.addLocalImport = false;
                    }
                }
            }

            if (realImportRep.length() > 0 && !this.addLocalImport) {

                //Workaround for: https://sourceforge.net/tracker/?func=detail&aid=2697165&group_id=85796&atid=577329
                //when importing from __future__ import with_statement, we actually want to add a 'with' token, not
                //with_statement token.
                boolean isWithStatement = realImportRep.equals("from __future__ import with_statement");
                if (isWithStatement) {
                    this.fReplacementString = "with";
                }

                if (groupImports) {
                    try {
                        realImportHandleInfo = new ImportHandleInfo(realImportRep);
                        PyImportsHandling importsHandling = new PyImportsHandling(document);
                        for (ImportHandle handle : importsHandling) {
                            if (handle.contains(realImportHandleInfo)) {
                                lineToAddImport = -2; //signal that there's no need to find a line available to add the import
                                break;

                            } else if (groupInto == null && realImportHandleInfo.getFromImportStr() != null) {
                                List<ImportHandleInfo> handleImportInfo = handle.getImportInfo();

                                for (ImportHandleInfo importHandleInfo : handleImportInfo) {
                                    if (realImportHandleInfo.getFromImportStr() == null) {
                                        continue;
                                    }
                                    if (realImportHandleInfo.getFromImportStrWithoutUnwantedChars().equals(
                                            importHandleInfo.getFromImportStrWithoutUnwantedChars())) {
                                        List<String> commentsForImports = importHandleInfo.getCommentsForImports();
                                        if (commentsForImports.size() > 0
                                                && commentsForImports.get(commentsForImports.size() - 1)
                                                        .length() == 0) {
                                            groupInto = importHandleInfo;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (ImportNotRecognizedException e1) {
                        Log.log(e1);//that should not happen at this point
                    }
                }

                if (lineToAddImport == -1) {
                    boolean isFutureImport = PySelection.isFutureImportLine(this.realImportRep);
                    lineToAddImport = selection.getLineAvailableForImport(isFutureImport);
                }
            } else {
                lineToAddImport = -1;
            }
            String delimiter = PyAction.getDelimiter(document);

            appliedWithTrigger = trigger == '.' || trigger == '(';
            String appendForTrigger = "";
            if (appliedWithTrigger) {
                if (trigger == '(') {
                    appendForTrigger = "()";

                } else if (trigger == '.') {
                    appendForTrigger = ".";
                }
            }

            //if the trigger is ')', just let it apply regularly -- so, ')' will only be added if it's already in the completion.

            //first do the completion
            if (fReplacementString.length() > 0) {
                int dif = offset - fReplacementOffset;
                document.replace(offset - dif, dif + this.fLen, fReplacementString + appendForTrigger);
            }
            if (this.addLocalImport) {
                if (addAsLocalImport(previousLineThatStartsScope, ps, delimiter)) {
                    return;
                }
            }

            if (groupInto != null && realImportHandleInfo != null) {
                //let's try to group it
                final int maxCols;
                if (SharedCorePlugin.inTestMode()) {
                    maxCols = 80;
                } else {
                    IPreferenceStore chainedPrefStore = PydevPrefs.getChainedPrefStore();
                    maxCols = chainedPrefStore
                            .getInt(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_PRINT_MARGIN_COLUMN);
                }

                int endLine = groupInto.getEndLine();
                IRegion lineInformation = document.getLineInformation(endLine);
                String strToAdd = ", " + realImportHandleInfo.getImportedStr().get(0);

                String line = PySelection.getLine(document, endLine);
                if (line.length() + strToAdd.length() > maxCols) {
                    if (line.indexOf('#') == -1) {
                        //no comments: just add it in the next line
                        int len = line.length();
                        if (line.trim().endsWith(")")) {
                            len = line.indexOf(")");
                            strToAdd = "," + delimiter + indentString + realImportHandleInfo.getImportedStr().get(0);
                        } else {
                            strToAdd = ",\\" + delimiter + indentString + realImportHandleInfo.getImportedStr().get(0);
                        }

                        int end = lineInformation.getOffset() + len;
                        importLen = strToAdd.length();
                        document.replace(end, 0, strToAdd);
                        return;

                    }

                } else {
                    //regular addition (it won't pass the number of columns expected).
                    line = PySelection.getLineWithoutCommentsOrLiterals(line);
                    int len = line.length();
                    if (line.trim().endsWith(")")) {
                        len = line.indexOf(")");
                    }

                    int end = lineInformation.getOffset() + len;
                    importLen = strToAdd.length();
                    document.replace(end, 0, strToAdd);
                    return;
                }
            }

            //if we got here, it hasn't been added in a grouped way, so, let's add it in a new import
            if (lineToAddImport >= 0 && lineToAddImport <= document.getNumberOfLines()) {
                IRegion lineInformation = document.getLineInformation(lineToAddImport);
                String strToAdd = realImportRep + delimiter;
                importLen = strToAdd.length();
                document.replace(lineInformation.getOffset(), 0, strToAdd);
                return;
            }

        } catch (BadLocationException x) {
            Log.log(x);
        }
    }

    public boolean addAsLocalImport(LineStartingScope previousLineThatStartsScope, PySelection ps, String delimiter) {
        //All the code below is because we don't want to work with a generated AST (as it may not be there),
        //so, we go to the previous scope, find out the valid indent inside it and then got backwards
        //from the position we're in now to find the closer location to where we're now where we're
        //actually able to add the import.
        try {
            int iLineStartingScope;
            if (previousLineThatStartsScope != null) {
                iLineStartingScope = previousLineThatStartsScope.iLineStartingScope;

                // Ok, we have the line where the scope starts... now, we have to check where that declaration
                // is finished (i.e.: def my( \n\n\n ): <- only after the ):
                Tuple<List<String>, Integer> tuple = new PySelection(ps.getDoc(), iLineStartingScope, 0)
                        .getInsideParentesisToks(false);
                if (tuple != null) {
                    iLineStartingScope = ps.getLineOfOffset(tuple.o2);
                }

                //Go to a non-empty line from the line we have and the line we're currently in.
                int iLine = iLineStartingScope + 1;
                String line = ps.getLine(iLine);
                final int startLineIndex = ps.getStartLineIndex(); // startLineIndex is our cursor line
                while (iLine < startLineIndex && (line.startsWith("#") || line.trim().length() == 0)) {
                    iLine++;
                    line = ps.getLine(iLine);
                }
                if (iLine >= startLineIndex) {
                    //Sanity check!
                    iLine = startLineIndex;
                    line = ps.getLine(iLine);
                }
                final String indent = line.substring(0, PySelection.getFirstCharPosition(line));

                if (getAddLocalImportsOnTopOfMethod()) {
                    if (iLine < startLineIndex) {
                        // Ok, should be on top of the function, but still, after the docstring.
                        String line2 = ps.getLine(iLine);
                        String trimmed = line2.trim();
                        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) {
                            ParsingUtils parsingUtils = ParsingUtils.create(ps.getDoc());
                            int index = line2.indexOf("'");
                            int index2 = line2.indexOf("\"");
                            int use;
                            if (index < 0) {
                                use = index2;
                            } else if (index2 < 0) {
                                use = index;
                            } else {
                                use = Math.min(index, index2);
                            }
                            int newOffset = parsingUtils.eatLiterals(null,
                                    ps.getAbsoluteCursorOffset(iLine, use));
                            int lineOfOffset = ps.getLineOfOffset(newOffset) + 1;
                            iLine = lineOfOffset;
                        }

                        // Also, if there's an import block, keep on going (make it the last import).
                        int j = iLine;
                        while (j < startLineIndex) {
                            line2 = ps.getLine(j);
                            trimmed = line2.trim();
                            if (trimmed.length() == 0) {
                                j++;
                                // Just a new line won't update the iLine
                                continue;
                            }
                            if (PySelection.isImportLine(trimmed)) {
                                PyDocIterator docIterator = new PyDocIterator(ps.getDoc(), true, true, false,
                                        false);
                                docIterator.setStartingOffset(ps.getLineOffset(j));
                                String str = docIterator.next();

                                if (str.contains("(")) { //we have something like from os import (pipe,\nfoo)
                                    while (docIterator.hasNext()) {
                                        if (str.contains(")")) {
                                            j = docIterator.getLastReturnedLine() + 1;
                                            break;
                                        } else {
                                            str = docIterator.next();
                                        }
                                    }
                                } else if (StringUtils.endsWith(str.trim(), '\\')) {
                                    while (docIterator.hasNext()) {
                                        if (!StringUtils.endsWith(str.trim(), '\\')) {
                                            j = docIterator.getLastReturnedLine() + 1;
                                            break;
                                        }
                                        str = docIterator.next();
                                    }
                                } else {
                                    j++;
                                }

                                iLine = j;
                                continue;
                            }
                            break;
                        }
                        line = ps.getLine(iLine);
                    }

                } else {
                    //Ok, all good so far, now, this would add the line to the beginning of
                    //the element (after the if statement, def, etc.), let's try to put
                    //it closer to where we're now (but still in a valid position).
                    int firstCharPos = PySelection.getFirstCharPosition(line);
                    int j = startLineIndex;
                    while (j >= 0) {
                        String line2 = ps.getLine(j);
                        if (PySelection.getFirstCharPosition(line2) == firstCharPos) {
                            iLine = j;
                            break;
                        }
                        if (j == iLineStartingScope) {
                            break;
                        }
                        j--;
                    }
                }

                String strToAdd = indent + realImportRep + delimiter;
                ps.addLine(strToAdd, iLine - 1); //Will add it just after the line passed as a parameter.
                importLen = strToAdd.length();
                return true;
            }
        } catch (Exception e) {
            Log.log(e); //Something went wrong, add it as global (i.e.: BUG)
        }
        return false;
    }

    @Override
    public Point getSelection(IDocument document) {
        if (newForcedOffset >= 0) {
            return new Point(newForcedOffset, 0);
        }

        int pos = fReplacementOffset + fReplacementString.length() + importLen;
        if (appliedWithTrigger) {
            pos += 1;
        }

        return new Point(pos, 0);
    }

    @Override
    public final String getInternalDisplayStringRepresentation() {
        return fReplacementString;
    }

    /**
     * If another proposal with the same name exists, this method will be called to determine if
     * both completions should coexist or if one of them should be removed.
     */
    @Override
    public int getOverrideBehavior(ICompletionProposal curr) {
        if (curr instanceof CtxInsensitiveImportComplProposal) {
            if (curr.getDisplayString().equals(getDisplayString())) {
                return BEHAVIOR_IS_OVERRIDEN;
            } else {
                return BEHAVIOR_COEXISTS;
            }
        } else {
            return BEHAVIOR_IS_OVERRIDEN;
        }
    }

}