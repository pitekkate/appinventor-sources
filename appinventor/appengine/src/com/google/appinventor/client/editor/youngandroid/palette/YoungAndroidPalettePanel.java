// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2009-2011 Google, All Rights reserved
// Copyright 2011-2017 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client.editor.youngandroid.palette;

import com.google.appinventor.client.editor.simple.SimpleComponentDatabase;
import com.google.appinventor.client.editor.simple.palette.AbstractPalettePanel;
import com.google.appinventor.client.editor.simple.palette.SimplePalettePanel;
import com.google.appinventor.client.editor.youngandroid.YaFormEditor;
import com.google.appinventor.client.wizards.ComponentImportWizard;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.ui.Anchor;
import com.google.gwt.user.client.ui.HasHorizontalAlignment;
import com.google.gwt.user.client.ui.VerticalPanel;

import static com.google.appinventor.client.Ode.MESSAGES;

/**
 * Panel showing Simple components which can be dropped onto the Young Android
 * visual designer panel.
 *
 * @author lizlooney@google.com (Liz Looney)
 */
public class YoungAndroidPalettePanel extends AbstractPalettePanel<SimpleComponentDatabase, YaFormEditor> {

  /**
   * Creates a new component palette panel.
   *
   * @param editor parent editor of this panel
   */
  public YoungAndroidPalettePanel(YaFormEditor editor) {
    super(editor, new YoungAndroidComponentFactory(editor), ComponentCategory.USERINTERFACE, ComponentCategory.LAYOUT, ComponentCategory.MEDIA,
        ComponentCategory.ANIMATION, ComponentCategory.MAPS, ComponentCategory.SENSORS, ComponentCategory.SOCIAL, ComponentCategory.STORAGE,
        ComponentCategory.EXPERIMENTAL, ComponentCategory.EXTENSION, ComponentCategory.INTERNAL);

    // If a category has a palette helper, add it to the paletteHelpers map here.
    simplePaletteItems = new HashMap<String, SimplePaletteItem>();
    translationMap = new HashMap<String, String>();
    panel = new VerticalPanel();
    panel.setWidth("100%");

    for (String component : COMPONENT_DATABASE.getComponentNames()) {
      String translationName = ComponentTranslationTable.getComponentName(component).toLowerCase();
      arrayString.push(translationName);
      translationMap.put(translationName, component);
    }

    searchText = new TextBox();
    searchText.setWidth("100%");
    searchText.getElement().setPropertyString("placeholder", MESSAGES.searchComponents());
    searchText.getElement().setAttribute("style", "width: 100%; box-sizing: border-box;");

    searchText.addKeyUpHandler(new SearchKeyUpHandler());
    searchText.addKeyPressHandler(new ReturnKeyHandler());
    searchText.addKeyDownHandler(new EscapeKeyDownHandler());
    searchText.addBlurHandler(new BlurHandler() {
      @Override
      public void onBlur(BlurEvent event) {
        doSearch();
      }
    });
    searchText.addChangeHandler(new ChangeHandler() {
      @Override
      public void onChange(ChangeEvent event) {
        doSearch();
      }
    });

    /* User presses the slash key, the search text box is focused */
    RootPanel.get().addDomHandler(new KeyDownHandler() {
      @Override
      public void onKeyDown(KeyDownEvent event) {
        DesignToolbar designToolbar = Ode.getInstance().getDesignToolbar();
        if (designToolbar.currentView == DesignToolbar.View.FORM && event.getNativeKeyCode() == 191
                && !isTextboxFocused() && !event.isAltKeyDown()) {
          {
            event.preventDefault();
            searchText.setFocus(true);
          }
        }
      }
    }, KeyDownEvent.getType());

    panel.setSpacing(3);
    panel.add(searchText);
    panel.setWidth("100%");

    searchResults = new VerticalPanel();
    searchResults.setWidth("100%");
    stackPalette.setWidth("100%");

    initWidget(panel);
    panel.add(searchResults);
    panel.add(stackPalette);

    for (ComponentCategory category : ComponentCategory.values()) {
      if (showCategory(category)) {
        addComponentCategory(category);
      }
    }
    stackPalette.show(0);
    initExtensionPanel();
  }

   /**
   *  Automatic search and list results as users input the string
   */
  private class SearchKeyUpHandler implements KeyUpHandler {
    @Override
    public void onKeyUp(KeyUpEvent event) {
      doSearch();
    }
  }

  /**
   *  Users press escape button, results and searchText will be cleared
   */
  private class EscapeKeyDownHandler implements KeyDownHandler {
    @Override
    public void onKeyDown(KeyDownEvent event) {
      if (event.getNativeKeyCode() == KeyCodes.KEY_ESCAPE) {
        searchResults.clear();
        searchText.setText("");
      }
    }
  }

  /**
   *  Users press enter button, results will be added to searchResults panel
   */
  private class ReturnKeyHandler implements KeyPressHandler {
     @Override
      public void onKeyPress(KeyPressEvent event) {
        switch (event.getCharCode()) {
          case KeyCodes.KEY_END:
          case KeyCodes.KEY_DELETE:
          case KeyCodes.KEY_BACKSPACE:
            doSearch();
            break;
        }
      }
  }

  private void doSearch() {
    doSearch(false);
  }

  /**
   *  User clicks on searchButton and results will be added to searchResults panel
   */
  private void doSearch(boolean force) {
    String search_str = searchText.getText().trim().toLowerCase();
    if (search_str.equals(lastSearch) && !force) {
      // nothing to do here.
      return;
    }
    // Empty strings will return nothing
    if (search_str.length() != 0) {
      long start = System.currentTimeMillis();
      // Remove previous search results
      searchResults.clear();
      Iterable<String> allComponents = filter(search_str);
      for (String name : allComponents) {
        if (translationMap.containsKey(name)) {
          final String codeName = translationMap.get(name);
          if (simplePaletteItems.containsKey(codeName)) {
            searchResults.add(searchSimplePaletteItems.get(codeName));
          }
        }
      }
    } else {
      searchResults.clear();
    }
    lastSearch = search_str;
  }

  private static boolean showCategory(ComponentCategory category) {
    if (category == ComponentCategory.UNINITIALIZED) {
      return false;
    }
    // We should only show FUTURE components if the future feature flag is enabled...
    if (category == ComponentCategory.FUTURE &&
        !AppInventorFeatures.enableFutureFeatures()) {
      return false;
    }
    if (category == ComponentCategory.INTERNAL &&
        !AppInventorFeatures.showInternalComponentsCategory()) {
      return false;
    }
    return true;
  }

  /**
   * Loads all components to be shown on this palette.  Specifically, for
   * each component (except for those whose category is UNINITIALIZED, or
   * whose category is INTERNAL and we're running on a production server,
   * or who are specifically marked as not to be shown on the palette),
   * this creates a corresponding {@link SimplePaletteItem} with the passed
   * {@link DropTargetProvider} and adds it to the panel corresponding to
   * its category.
   *
   * @param dropTargetProvider provider of targets that palette items can be
   *                           dropped on
   */
  @Override
  public void loadComponents(DropTargetProvider dropTargetProvider) {
    this.dropTargetProvider = dropTargetProvider;
    for (String component : COMPONENT_DATABASE.getComponentNames()) {
      this.addComponent(component);
    }
  }

  public void loadComponents() {
    for (ComponentCategory category : ComponentCategory.values()) {
      if (showCategory(category)) {
        addComponentCategory(category);
      }
    }
    initExtensionPanel();
    for (String component : COMPONENT_DATABASE.getComponentNames()) {
      this.addComponent(component);
    }
    stackPalette.show(0);
  }

  public void reloadComponentsFromSet(Set<String> set) {
    clearComponents();
    initExtensionPanel();
    for (String component : set) {
      addComponent(component);
    }
    stackPalette.show(0);
  }

  @Override
  public SimplePalettePanel copy() {
    YoungAndroidPalettePanel copy = new YoungAndroidPalettePanel(this.editor);
    copy.setSize("100%", "100%");
    return copy;
  }

  @Override
  public VerticalPanel addComponentCategory(ComponentCategory category) {
    VerticalPanel panel = super.addComponentCategory(category);
    if (category == ComponentCategory.EXTENSION) {
      initExtensionPanel(panel);
    }
    return panel;
  }

  private void initExtensionPanel(VerticalPanel panel) {
    Anchor addComponentAnchor = new Anchor(MESSAGES.importExtensionMenuItem());
    addComponentAnchor.setStylePrimaryName("ode-ExtensionAnchor");
    addComponentAnchor.addClickHandler(new ClickHandler() {
      @Override
      public void onClick(ClickEvent event) {
        new ComponentImportWizard().center();
      }
    });

    panel.add(addComponentAnchor);
    panel.setCellHorizontalAlignment(addComponentAnchor, HasHorizontalAlignment.ALIGN_CENTER);
  }
}
