/*
 * jGnash, a personal finance application
 * Copyright (C) 2001-2015 Craig Cavanaugh
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.uifx.views.budget;

import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import jgnash.engine.Engine;
import jgnash.engine.EngineFactory;
import jgnash.engine.budget.Budget;
import jgnash.engine.budget.BudgetResultsExport;
import jgnash.engine.message.Message;
import jgnash.engine.message.MessageBus;
import jgnash.engine.message.MessageChannel;
import jgnash.engine.message.MessageListener;
import jgnash.uifx.StaticUIMethods;
import jgnash.uifx.util.FXMLUtils;
import jgnash.uifx.views.main.MainApplication;

/**
 * @author Craig Cavanaugh
 */
public class BudgetViewController implements MessageListener {

    private static final String EXPORT_DIR = "exportDir";

    private static final String LAST_BUDGET = "lastBudget";

    @FXML
    private BorderPane borderPane;

    @FXML
    private Button exportButton;

    @FXML
    private Button propertiesButton;

    @FXML
    private ComboBox<Budget> availableBudgetsComboBox;

    @FXML
    private ResourceBundle resources;

    private BudgetTableController budgetTableController;

    private final Preferences preferences = Preferences.userNodeForPackage(BudgetViewController.class);

    @FXML
    private void initialize() {
        exportButton.disableProperty().bind(availableBudgetsComboBox.valueProperty().isNull());
        propertiesButton.disableProperty().bind(availableBudgetsComboBox.valueProperty().isNull());

        // push to end of application thread to avoid a race
        Platform.runLater(() -> {
            budgetTableController
                    = FXMLUtils.loadFXML(o -> borderPane.setCenter((Node) o), "BudgetTable.fxml", resources);

            availableBudgetsComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue != null) {
                    preferences.put(LAST_BUDGET, newValue.getUuid());
                }

                Platform.runLater(() -> budgetTableController.budgetProperty().setValue(newValue));
            });

            loadComboBox();

            MessageBus.getInstance().registerListener(BudgetViewController.this, MessageChannel.BUDGET,
                    MessageChannel.SYSTEM);
        });
    }

    private void loadComboBox() {
        final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
        Objects.requireNonNull(engine);

        // Create a sorted List of active budgets
        final List<Budget> budgetList = engine.getBudgetList();
        Collections.sort(budgetList);

        availableBudgetsComboBox.getItems().setAll(budgetList);

        final Budget lastBudget = engine.getBudgetByUuid(preferences.get(LAST_BUDGET, ""));

        if (budgetList.contains(lastBudget)) {
            availableBudgetsComboBox.setValue(lastBudget);
        } else if (availableBudgetsComboBox.getItems().size() > 0) {
            availableBudgetsComboBox.setValue(availableBudgetsComboBox.getItems().get(0));
        }
    }

    @FXML
    private void handleExportAction() {
        Objects.requireNonNull(budgetTableController);

        final Preferences pref = Preferences.userNodeForPackage(BudgetViewController.class);
        final FileChooser fileChooser = new FileChooser();

        fileChooser.setInitialDirectory(new File(pref.get(EXPORT_DIR, System.getProperty("user.home"))));

        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(resources.getString("Label.SpreadsheetFiles") + " (*.xls, *.xlsx)",
                        "*.xls", "*.xlsx")
        );

        final File file = fileChooser.showSaveDialog(MainApplication.getInstance().getPrimaryStage());

        if (file != null) {
            pref.put(EXPORT_DIR, file.getParentFile().getAbsolutePath());

            final Task<Void> exportTask = new Task<Void>() {
                @Override
                protected Void call() throws Exception {
                    updateMessage(resources.getString("Message.PleaseWait"));
                    updateProgress(-1, Long.MAX_VALUE);

                    BudgetResultsExport.exportBudgetResultsModel(file, budgetTableController.getBudgetResultsModel());
                    return null;
                }
            };

            new Thread(exportTask).start();

            StaticUIMethods.displayTaskProgress(exportTask);
        }
    }

    @FXML
    private void handleManagerAction() {
        final URL fxmlUrl = BudgetManagerDialogController.class.getResource("BudgetManagerDialog.fxml");
        final Stage stage = FXMLUtils.loadFXML(fxmlUrl, resources);
        stage.setTitle(resources.getString("Title.BudgetManager"));

        stage.show();
        stage.setResizable(false);
    }

    @FXML
    private void handlePropertiesAction() {
        final ObjectProperty<BudgetPropertiesDialogController> controllerObjectProperty = new SimpleObjectProperty<>();

        final URL fxmlUrl = BudgetPropertiesDialogController.class.getResource("BudgetPropertiesDialog.fxml");
        final Stage stage = FXMLUtils.loadFXML(fxmlUrl, controllerObjectProperty, resources);
        stage.setTitle(resources.getString("Title.BudgetProperties"));

        controllerObjectProperty.get().setBudget(availableBudgetsComboBox.getValue());

        stage.show();
        stage.setResizable(false);
    }

    @Override
    public void messagePosted(final Message message) {
        switch (message.getEvent()) {
            case FILE_CLOSING:
                MessageBus.getInstance().unregisterListener(this, MessageChannel.BUDGET, MessageChannel.SYSTEM);
                Platform.runLater(() -> availableBudgetsComboBox.getItems().clear());
                break;
            case BUDGET_REMOVE:
            case BUDGET_ADD:
            case BUDGET_UPDATE:
                Platform.runLater(BudgetViewController.this::loadComboBox);
                break;
            default:
                break;
        }
    }
}