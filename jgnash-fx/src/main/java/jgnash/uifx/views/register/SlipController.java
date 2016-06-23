/*
 * jGnash, a personal finance application
 * Copyright (C) 2001-2016 Craig Cavanaugh
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
package jgnash.uifx.views.register;

import java.time.LocalDate;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;

import jgnash.engine.Account;
import jgnash.engine.Engine;
import jgnash.engine.EngineFactory;
import jgnash.engine.InvestmentTransaction;
import jgnash.engine.ReconcileManager;
import jgnash.engine.Transaction;
import jgnash.engine.TransactionEntry;
import jgnash.engine.TransactionFactory;
import jgnash.engine.TransactionType;
import jgnash.uifx.Options;
import jgnash.uifx.StaticUIMethods;
import jgnash.uifx.util.ValidationFactory;
import jgnash.util.NotNull;

/**
 * Transaction Entry Controller for Credits and Debits.
 *
 * @author Craig Cavanaugh
 */
public class SlipController extends AbstractSlipController {

    @FXML
    protected Button cancelButton;

    @FXML
    protected Button enterButton;

    @FXML
    private Button splitsButton;

    @FXML
    private AccountExchangePane accountExchangePane;

    private final SimpleListProperty<TransactionEntry> transactionEntriesProperty =
            new SimpleListProperty<>(FXCollections.observableArrayList());

    private SlipType slipType;

    private final SimpleObjectProperty<TransactionEntry> modEntry = new SimpleObjectProperty<>();

    private final BooleanProperty concatenatedProperty = new SimpleBooleanProperty();

    @FXML
    @Override
    public void initialize() {
        super.initialize();

        // Bind necessary properties to the exchange panel
        accountExchangePane.baseAccountProperty().bind(accountProperty());
        accountExchangePane.amountProperty().bindBidirectional(amountField.decimalProperty());
        accountExchangePane.amountEditableProperty().bind(amountField.editableProperty());

        // Bind the enter button, effectively negates the need for validation
        if (enterButton != null) {  // enter button may not have been initialized if used for an investment slip
            enterButton.disableProperty().bind(Bindings.or(amountField.textProperty().isEmpty(),
                    accountExchangePane.selectedAccountProperty().isNull()));
        }

        amountField.editableProperty().bind(transactionEntriesProperty.emptyProperty());
        accountExchangePane.disableProperty().bind(transactionEntriesProperty.emptyProperty().not()
                .or(modEntry.isNotNull()));

        memoTextField.disableProperty().bind(concatenatedProperty);
    }

    void setSlipType(final SlipType slipType) {
        this.slipType = slipType;
    }

    @Override
    public void modifyTransaction(@NotNull final Transaction transaction) {
        if (transaction.areAccountsLocked()) {
            clearForm();
            StaticUIMethods.displayError(resources.getString("Message.TransactionModifyLocked"));
            return;
        }

        newTransaction(transaction); // load the form

        modTrans = transaction; // save reference to old transaction
        modTrans = attachmentPane.modifyTransaction(modTrans);

        // Set state of memo concatenation
        concatenatedProperty.setValue(modTrans.isMemoConcatenated());

        if (!canModifyTransaction(transaction) && transaction.getTransactionType() == TransactionType.SPLITENTRY) {
            for (final TransactionEntry entry : transaction.getTransactionEntries()) {
                if (entry.getCreditAccount().equals(accountProperty().get()) || entry.getDebitAccount().equals(accountProperty().get())) {
                    modEntry.setValue(entry);

                    concatenatedProperty.setValue(false); // override to allow editing the entry
                    break;
                }
            }

            if (modEntry.get() == null) {
                Logger logger = Logger.getLogger(SlipController.class.getName());
                logger.warning("Was not able to modify the transaction");
            }
        }
    }

    @Override
    public boolean validateForm() {
        boolean result = super.validateForm();

        if (accountExchangePane.getSelectedAccount() == null) {
            ValidationFactory.showValidationError(accountExchangePane, resources.getString("Message.Error.Value"));
            result = false;
        }

        return result;
    }

    @NotNull
    @Override
    public Transaction buildTransaction() {

        Transaction transaction;

        final LocalDate date = datePicker.getValue();

        if (transactionEntriesProperty.size() > 0) { // build a split transaction
            transaction = new Transaction();

            transaction.setDate(date);
            transaction.setNumber(numberComboBox.getValue());
            transaction.setMemo(Options.concatenateMemosProperty().get() ? Transaction.CONCATENATE
                    : memoTextField.getText());
            transaction.setPayee(payeeTextField.getText());

            transaction.addTransactionEntries(transactionEntriesProperty);
        } else {  // double entry transaction
            final int signum = amountField.getDecimal().signum();

            final Account selectedAccount;

            if (modTrans != null && modTrans.areAccountsHidden()) {
                selectedAccount = getOppositeSideAccount(modTrans);
            } else {
                selectedAccount = accountExchangePane.getSelectedAccount();
            }

            if (slipType == SlipType.DECREASE && signum >= 0 || slipType == SlipType.INCREASE && signum == -1) {
                if (hasEqualCurrencies()) {
                    transaction = TransactionFactory.generateDoubleEntryTransaction(selectedAccount,
                            accountProperty.get(), amountField.getDecimal().abs(), date, memoTextField.getText(),
                            payeeTextField.getText(), numberComboBox.getValue());
                } else {
                    transaction = TransactionFactory.generateDoubleEntryTransaction(selectedAccount,
                            accountProperty.get(), accountExchangePane.exchangeAmountProperty().get().abs(),
                            amountField.getDecimal().abs().negate(), date, memoTextField.getText(),
                            payeeTextField.getText(), numberComboBox.getValue());
                }
            } else {
                if (hasEqualCurrencies()) {
                    transaction = TransactionFactory.generateDoubleEntryTransaction(accountProperty.get(),
                            selectedAccount, amountField.getDecimal().abs(), date, memoTextField.getText(),
                            payeeTextField.getText(), numberComboBox.getValue());
                } else {
                    transaction = TransactionFactory.generateDoubleEntryTransaction(accountProperty.get(),
                            selectedAccount, amountField.getDecimal().abs(),
                            accountExchangePane.exchangeAmountProperty().get().abs().negate(), date,
                            memoTextField.getText(), payeeTextField.getText(), numberComboBox.getValue());
                }
            }
        }

        ReconcileManager.reconcileTransaction(accountProperty.get(), transaction, getReconciledState());

        return transaction;
    }

    private TransactionEntry buildTransactionEntry() {
        final TransactionEntry entry = new TransactionEntry();
        entry.setMemo(memoTextField.getText());

        final int signum = amountField.getDecimal().signum();

        if (slipType == SlipType.DECREASE && signum >= 0 || slipType == SlipType.INCREASE && signum < 0) {
            entry.setCreditAccount(accountExchangePane.getSelectedAccount());
            entry.setDebitAccount(accountProperty.get());

            if (hasEqualCurrencies()) {
                entry.setAmount(amountField.getDecimal().abs());
            } else {
                entry.setDebitAmount(accountExchangePane.exchangeAmountProperty().get().abs().negate());
            }
        } else {
            entry.setCreditAccount(accountProperty.get());
            entry.setDebitAccount(accountExchangePane.getSelectedAccount());

            if (hasEqualCurrencies()) {
                entry.setAmount(amountField.getDecimal().abs());
            } else {
                entry.setCreditAmount(accountExchangePane.exchangeAmountProperty().get().abs());
            }
        }

        entry.setReconciled(accountProperty.get(), getReconciledState());

        return entry;
    }

    private boolean hasEqualCurrencies() {
        return accountProperty.get().getCurrencyNode().equals(accountExchangePane.getSelectedAccount().getCurrencyNode());
    }

    private Account getOppositeSideAccount(final Transaction t) {
        TransactionEntry entry = t.getTransactionEntries().get(0);

        if (entry.getCreditAccount().equals(accountProperty.get())) {
            return entry.getDebitAccount();
        }
        return entry.getCreditAccount();
    }

    private void newTransaction(final Transaction t) {
        clearForm();

        amountField.setDecimal(t.getAmount(accountProperty().get()).abs());

        memoTextField.setText(t.getMemo());
        payeeTextField.setText(t.getPayee());
        numberComboBox.setValue(t.getNumber());
        datePicker.setValue(t.getLocalDate());
        setReconciledState(t.getReconciled(accountProperty().get()));

        if (t.getTransactionType() == TransactionType.SPLITENTRY) {
            accountExchangePane.setSelectedAccount(t.getCommonAccount()); // display common account

            if (canModifyTransaction(t)) { // split common account is the same as the base account

                //  clone the splits for modification
                transactionEntriesProperty.clear();

                for (final TransactionEntry entry : t.getTransactionEntries()) {
                    try {
                        transactionEntriesProperty.add((TransactionEntry) entry.clone());
                    } catch (final CloneNotSupportedException e) {
                        Logger.getLogger(SlipController.class.getName()).log(Level.SEVERE, e.getLocalizedMessage(), e);
                    }
                }

                amountField.setDecimal(t.getAmount(accountProperty().get()).abs());
            } else { // not the same common account, can only modify the entry
                splitsButton.setDisable(true);
                payeeTextField.setEditable(false);
                numberComboBox.setDisable(true);
                datePicker.setEditable(false);

                memoTextField.setText(t.getMemo(accountProperty.get()));   // Override

                amountField.setDecimal(t.getAmount(accountProperty().get()).abs());

                for (final TransactionEntry entry : t.getTransactionEntries()) {
                    if (entry.getCreditAccount() == accountProperty().get()) {
                        accountExchangePane.setExchangedAmount(entry.getDebitAmount().abs());
                        break;
                    } else if (entry.getDebitAccount() == accountProperty().get()) {
                        accountExchangePane.setExchangedAmount(entry.getCreditAmount());
                        break;
                    }
                }
            }
        } else if (t instanceof InvestmentTransaction) {
            Logger logger = Logger.getLogger(SlipController.class.getName());
            logger.warning("unsupported transaction type");
        } else { // DoubleEntryTransaction
            datePicker.setEditable(true);
        }

        // setup the accountCombo correctly
        if (t.getTransactionType() == TransactionType.DOUBLEENTRY) {
            TransactionEntry entry = t.getTransactionEntries().get(0);

            if (slipType == SlipType.DECREASE) {
                accountExchangePane.setSelectedAccount(entry.getCreditAccount());
                accountExchangePane.setExchangedAmount(entry.getCreditAmount());
            } else {
                accountExchangePane.setSelectedAccount(entry.getDebitAccount());
                accountExchangePane.setExchangedAmount(entry.getDebitAmount().abs());
            }
        }
    }

    @Override
    public void clearForm() {
        super.clearForm();

        // Not yet a split transaction
        concatenatedProperty.setValue(false);

        transactionEntriesProperty.clear();   // clear an old transaction entries

        modEntry.setValue(null);

        accountExchangePane.setExchangedAmount(null);

        splitsButton.setDisable(false);
    }

    @Override
    boolean canModifyTransaction(final Transaction t) {
        boolean result = false; // fail unless proven otherwise

        switch (t.getTransactionType()) {
            case DOUBLEENTRY:
                final TransactionEntry entry = t.getTransactionEntries().get(0);

                // Prevent loading of a single entry scenario into the form.  The user may not detect it
                // and the engine will throw an exception if undetected.
                if (slipType == SlipType.DECREASE) {
                    if (!accountProperty().get().equals(entry.getCreditAccount())) {
                        result = true;
                    }
                } else {
                    if (!accountProperty().get().equals(entry.getDebitAccount())) {
                        result = true;
                    }
                }

                break;
            case SPLITENTRY:
                if (t.getCommonAccount().equals(accountProperty.get())) {
                    result = true;
                }
                break;
            default:
                break;
        }

        return result;
    }

    @Override
    public void handleEnterAction() {
        if (validateForm()) {
            if (modEntry.get() != null && modTrans != null) {
                try {
                    final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
                    Objects.requireNonNull(engine);

                    // clone the transaction
                    final Transaction t = (Transaction) modTrans.clone();

                    // remove the modifying entry from the clone
                    t.removeTransactionEntry(modEntry.get());

                    // generate new TransactionEntry
                    final TransactionEntry e = buildTransactionEntry();

                    // add it to the clone
                    t.addTransactionEntry(e);

                    ReconcileManager.reconcileTransaction(accountProperty.get(), t, getReconciledState());

                    if (engine.removeTransaction(modTrans)) {
                        engine.addTransaction(t);
                    }

                    clearForm();
                    focusFirstComponent();
                } catch (CloneNotSupportedException e) {
                    Logger.getLogger(SlipController.class.getName()).log(Level.SEVERE, e.getLocalizedMessage(), e);
                }
            } else {
                super.handleEnterAction();
            }
        }

    }

    @FXML
    private void splitsAction() {
        final SplitTransactionDialog splitsDialog = new SplitTransactionDialog();
        splitsDialog.accountProperty().setValue(accountProperty().get());
        splitsDialog.getTransactionEntries().setAll(transactionEntriesProperty);

        // Show the dialog and process the transactions when it closes
        splitsDialog.show(slipType, () -> {
            transactionEntriesProperty.setAll(splitsDialog.getTransactionEntries());
            amountField.setDecimal(splitsDialog.getBalance().abs());

            // If valid splits exist and the user has requested concatenation, show a preview of what will happen
            concatenatedProperty.setValue(Options.concatenateMemosProperty().get()
                    && transactionEntriesProperty.size() > 0);

            if (concatenatedProperty.get()) {
                memoTextField.setText(Transaction.getMemo(transactionEntriesProperty));
            }
        });
    }
}
