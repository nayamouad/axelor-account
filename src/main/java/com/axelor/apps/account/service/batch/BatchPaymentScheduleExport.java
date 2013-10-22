/**
 * Copyright (c) 2012-2013 Axelor. All Rights Reserved.
 *
 * The contents of this file are subject to the Common Public
 * Attribution License Version 1.0 (the “License”); you may not use
 * this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://license.axelor.com/.
 *
 * The License is based on the Mozilla Public License Version 1.1 but
 * Sections 14 and 15 have been added to cover use of software over a
 * computer network and provide for limited attribution for the
 * Original Developer. In addition, Exhibit A has been modified to be
 * consistent with Exhibit B.
 *
 * Software distributed under the License is distributed on an “AS IS”
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 *
 * The Original Code is part of "Axelor Business Suite", developed by
 * Axelor exclusively.
 *
 * The Original Developer is the Initial Developer. The Initial Developer of
 * the Original Code is Axelor.
 *
 * All portions of the code written by Axelor are
 * Copyright (c) 2012-2013 Axelor. All Rights Reserved.
 */
package com.axelor.apps.account.service.batch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.IAccount;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.PaymentScheduleLine;
import com.axelor.apps.account.service.CfonbService;
import com.axelor.apps.account.service.PaymentScheduleExportService;
import com.axelor.apps.account.service.payment.PaymentModeService;
import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Status;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.axelor.exception.service.TraceBackService;

public class BatchPaymentScheduleExport extends BatchStrategy {

	private static final Logger LOG = LoggerFactory.getLogger(BatchPaymentScheduleExport.class);
	
	private boolean stop = false;
	
	private BigDecimal totalAmount = BigDecimal.ZERO;
	
	private String updateCustomerAccountLog = "";
	
	@Inject
	public BatchPaymentScheduleExport(PaymentScheduleExportService paymentScheduleExportService, PaymentModeService paymentModeService, CfonbService cfonbService, BatchAccountCustomer batchAccountCustomer) {
		
		super(paymentScheduleExportService, paymentModeService, cfonbService, batchAccountCustomer);
		
	}

	@Override
	protected void start() throws IllegalArgumentException, IllegalAccessException {
	
		super.start();
		
		Company company = batch.getAccountingBatch().getCompany();
		
		company = Company.find(company.getId());
				
		switch (batch.getAccountingBatch().getDirectDebitExportTypeSelect()) {
		
		case IAccount.INVOICE_EXPORT:
			try {
				paymentScheduleExportService.checkDebitDate(batch.getAccountingBatch());
				paymentScheduleExportService.checkCompanyJournal(company);
				cfonbService.testCompanyExportCFONBField(company);
				this.testAccountingBatchBankDetails(batch.getAccountingBatch());
			} catch (AxelorException e) {
				TraceBackService.trace(new AxelorException("", e, e.getcategory()), IException.DIRECT_DEBIT, batch.getId());
				incrementAnomaly();
				stop = true;
			}
			break;
			
		case IAccount.MONTHLY_EXPORT:
			try {
				paymentScheduleExportService.checkDebitDate(batch.getAccountingBatch());
				paymentScheduleExportService.checkCompanyJournalMonthlyPayment(company);
				cfonbService.testCompanyExportCFONBField(company);
				this.testAccountingBatchBankDetails(batch.getAccountingBatch());
			} catch (AxelorException e) {
				TraceBackService.trace(new AxelorException("", e, e.getcategory()), IException.DIRECT_DEBIT, batch.getId());
				incrementAnomaly();
				stop = true;
			}
			break;	
			
		default:
			TraceBackService.trace(new AxelorException(String.format("Type de donnée inconnu pour le traitement %s", batch.getAccountingBatch().getActionSelect()),IException.INCONSISTENCY ), IException.DIRECT_DEBIT, batch.getId());
			incrementAnomaly();
			stop = true;
		}
		
		checkPoint();

	}

	@Override
	protected void process() {
		if(!stop)  {
			Company company = batch.getAccountingBatch().getCompany();
			
			company = Company.find(company.getId());
			
			switch (batch.getAccountingBatch().getDirectDebitExportTypeSelect()) {
				
				case IAccount.INVOICE_EXPORT:
					
					this.exportInvoiceAndPaymentScheduleBatch(company);
					
					break;
					
				case IAccount.MONTHLY_EXPORT:
					
					this.exportMonthlyPaymentBatch(company);
					
					break;	
			}	
				
			updateCustomerAccountLog += batchAccountCustomer.updateAccountingSituationMarked(Company.find(company.getId()));
		}	
		
	}
	
	
public void exportMonthlyPaymentBatch(Company company)  {
		
		List<PaymentScheduleLine> pslListGrandCompte = paymentScheduleExportService.getPaymentScheduleLineToDebit(
				Company.find(company.getId()), Batch.find(batch.getId()).getAccountingBatch().getDebitDate(), paymentScheduleExportService.getDebitPaymentMode(), true);
		
		LOG.debug("\n Liste des échéances grand comptes retenues {} \n", paymentScheduleExportService.toStringPaymentScheduleLineList(pslListGrandCompte));
		
		List<PaymentScheduleLine> paymentScheduleLineList = new ArrayList<PaymentScheduleLine>();
		
		
		if(!pslListGrandCompte.isEmpty())  {
			// Génération de l'écriture de paiement pour Mensu Grand Compte
			LOG.debug("Génération de l'écriture de paiement pour Mensu Grand Compte");
			
			paymentScheduleLineList.addAll(this.generateAllExportMensu(
					pslListGrandCompte, Company.find(company.getId()),
					paymentScheduleExportService.getDebitPaymentMode(),
					Status.all().filter("code = 'val'").fetchOne(),
					Company.find(company.getId()).getMajorAccountJournal(),
					true));
		}
		
		// Création du fichier d'export au format CFONB
		if(paymentScheduleLineList != null && paymentScheduleLineList.size() != 0)  {
			try  {
				
				cfonbService.exportCFONB(Batch.find(batch.getId()).getStartDate(), Batch.find(batch.getId()).getAccountingBatch().getDebitDate(), paymentScheduleLineList, Company.find(company.getId()), Batch.find(batch.getId()).getAccountingBatch().getBankDetails());
		
			} catch (AxelorException e) {
				
				TraceBackService.trace(new AxelorException(String.format("Batch d'export des prélèvements %s", batch.getId()), e, e.getcategory()), IException.DIRECT_DEBIT, batch.getId());
				
				incrementAnomaly();
				
			} catch (Exception e) {
				
				TraceBackService.trace(new Exception(String.format("Batch d'export des prélèvements %s", batch.getId()), e), IException.DIRECT_DEBIT, batch.getId());
				
				incrementAnomaly();
				
				LOG.error("Bug(Anomalie) généré(e) pour le batch d'export des prélèvements {}", batch.getId());
			
			}	
		}
	}
	

	/**
	 * Méthode permettant de générer l'ensemble des exports des prélèvements pour Mensu Masse et Mensu Grand Compte
	 * @param paymentScheduleExport
	 * 			Un objet d'export des prélèvements
	 * @param company
	 * 			Une société
	 * @param paymentMode
	 * 			Un mode de paiement
	 * @param statusVal
	 * 			Un status
	 * @param journal
	 * 			Un journal
	 * @param isMajorAccount
	 * 			Le traitement concerne le prélèvement des échéances de mensu grand compte ?
	 * @return 
	 * @return
	 * @throws AxelorException
	 */
	public List<PaymentScheduleLine> generateAllExportMensu (List<PaymentScheduleLine> pslList, Company company, PaymentMode paymentMode, Status statusVal, Journal journal, boolean isMajorAccount)  {
		
		Move move = null;
		
		try  {
			
			move = paymentScheduleExportService.createExportMensuMove(Journal.find(journal.getId()), Company.find(company.getId()), PaymentMode.find(paymentMode.getId()));
			
		} catch (AxelorException e) {
			
			TraceBackService.trace(new AxelorException(String.format("Batch d'export des prélèvements %s", batch.getId()), e, e.getcategory()), IException.DIRECT_DEBIT, batch.getId());
			
			incrementAnomaly();
			
			stop = true;
			
		} catch (Exception e) {
			
			TraceBackService.trace(new Exception(String.format("Batch d'export des prélèvements %s", batch.getId()), e), IException.DIRECT_DEBIT, batch.getId());
			
			incrementAnomaly();
			
			LOG.error("Bug(Anomalie) généré(e) pour le batch d'export des prélèvements {}", batch.getId());
		
			stop = true;
		}	
		
		int ref = 1;  // Initialisation du compteur d'échéances
		
		int i = 0;
		
		List<PaymentScheduleLine> pslListToExport = new ArrayList<PaymentScheduleLine>();
		
		if(!stop)  {
			for(PaymentScheduleLine paymentScheduleLine : pslList)  {
				
				try  {
					PaymentScheduleLine paymentScheduleLineToExport = paymentScheduleExportService.generateExportMensu(
							PaymentScheduleLine.find(paymentScheduleLine.getId()), pslList, Status.find(statusVal.getId()), Company.find(company.getId()), isMajorAccount, ref, Move.find(move.getId()));
					if(paymentScheduleLineToExport != null)  {
						ref++;
						i++;
						pslListToExport.add(paymentScheduleLineToExport);
						updatePaymentScheduleLine(paymentScheduleLineToExport);
						this.totalAmount = this.totalAmount.add(PaymentScheduleLine.find(paymentScheduleLine.getId()).getInTaxAmount());
					}
				} catch (AxelorException e) {
					
					TraceBackService.trace(new AxelorException(String.format("Prélèvement de l'échéance %s", paymentScheduleLine.getName()), e, e.getcategory()), IException.DIRECT_DEBIT, batch.getId());
					
					incrementAnomaly();
					
				} catch (Exception e) {
					
					TraceBackService.trace(new Exception(String.format("Prélèvement de l'échéance %s", paymentScheduleLine.getName()), e), IException.DIRECT_DEBIT, batch.getId());
					
					incrementAnomaly();
					
					LOG.error("Bug(Anomalie) généré(e) pour le Prélèvement de l'échéance {}", paymentScheduleLine.getName());
					
				} finally {
					
					if (i % 10 == 0) { JPA.clear(); }
		
				}	
			}
		}
		
		try  {
			if(ref != 1)  {
	
				// Récupération du compte banque prélèvement
				Account bankAccount = paymentModeService.getCompanyAccount(PaymentMode.find(paymentMode.getId()), Company.find(company.getId()));
				
				paymentScheduleExportService.createOppositeExportMensuMoveLine(Move.find(move.getId()), bankAccount, ref);
				
				paymentScheduleExportService.validateMove(Move.find(move.getId()));
			}
			else {
				paymentScheduleExportService.deleteMove(Move.find(move.getId()));
			}
		} catch (AxelorException e) {
			
			TraceBackService.trace(new AxelorException(String.format("Batch d'export des prélèvements %s", batch.getId()), e, e.getcategory()), IException.DIRECT_DEBIT, batch.getId());
			
			incrementAnomaly();
			
		} catch (Exception e) {
			
			TraceBackService.trace(new Exception(String.format("Batch d'export des prélèvements %s", batch.getId()), e), IException.DIRECT_DEBIT, batch.getId());
			
			incrementAnomaly();
			
			LOG.error("Bug(Anomalie) généré(e) pour le batch d'export des prélèvements {}", batch.getId());
		
		}	
		
		return pslListToExport;
		
	}

	
	
	public void exportInvoiceAndPaymentScheduleBatch(Company company)  {
		
		List<PaymentScheduleLine> paymentScheduleLineToExportList = this.exportPaymentSchedule(Company.find(company.getId()),
				PaymentMode.all().filter("self.code = 'DD'").fetchOne(),
				Status.all().filter("code = 'val'").fetchOne(), 
				Batch.find(batch.getId()).getAccountingBatch().getDebitDate());
		
		
		List<Invoice> invoiceToExportList = this.exportInvoice(Company.find(company.getId()),
				Batch.find(batch.getId()).getAccountingBatch().getDebitDate(), 
				PaymentMode.all().filter("self.code = 'DD'").fetchOne());
				
		
		// Création du fichier d'export au format CFONB
		if((paymentScheduleLineToExportList != null && paymentScheduleLineToExportList.size() != 0)
				|| (invoiceToExportList != null && invoiceToExportList.size() != 0))  {
			try  {
				
				cfonbService.exportCFONB(Batch.find(batch.getId()).getStartDate(), Batch.find(batch.getId()).getAccountingBatch().getDebitDate(), paymentScheduleLineToExportList, invoiceToExportList, Company.find(company.getId()), Batch.find(batch.getId()).getAccountingBatch().getBankDetails());
			
			} catch (AxelorException e) {
				
				TraceBackService.trace(new AxelorException(String.format("Batch d'export des prélèvements %s", batch.getId()), e, e.getcategory()), IException.DIRECT_DEBIT, batch.getId());
				
				incrementAnomaly();
				
			} catch (Exception e) {
				
				TraceBackService.trace(new Exception(String.format("Batch d'export des prélèvements %s", batch.getId()), e), IException.DIRECT_DEBIT, batch.getId());
				
				incrementAnomaly();
				
				LOG.error("Bug(Anomalie) généré(e) pour le batch d'export des prélèvements {}", batch.getId());
			
			}	
		}
	}
	
	
	public List<PaymentScheduleLine> exportPaymentSchedule(Company company, PaymentMode paymentMode, Status status, LocalDate scheduleDate)  {
		
		List<PaymentScheduleLine> paymentScheduleLineList = paymentScheduleExportService.getPaymentScheduleLineToDebit(Company.find(company.getId()), scheduleDate, PaymentMode.find(paymentMode.getId()));
		
		List<PaymentScheduleLine> paymentScheduleLineExportedList = new ArrayList<PaymentScheduleLine>();  // Liste des échéances de paiement exportés
		
		int i = 0;
		
		for(PaymentScheduleLine paymentScheduleLine : paymentScheduleLineList)  {
		
			try  {
				
				if(paymentScheduleLine.getMoveLineGenerated()!=null)  {
					PaymentScheduleLine paymentScheduleLineToExport = paymentScheduleExportService.exportPaymentScheduleLine(
							PaymentScheduleLine.find(paymentScheduleLine.getId()), 
							paymentScheduleLineList, 
							PaymentMode.find(paymentMode.getId()), 
							Company.find(company.getId()), 
							Status.find(status.getId()));
					
					if(paymentScheduleLineToExport != null)  {
						paymentScheduleLineExportedList.add(paymentScheduleLineToExport);
						updatePaymentScheduleLine(paymentScheduleLineToExport);
						this.totalAmount = this.totalAmount.add(cfonbService.getAmountRemainingFromPaymentMove(PaymentScheduleLine.find(paymentScheduleLine.getId())));
						i++;
					}
				}
				
			} catch (AxelorException e) {
				
				TraceBackService.trace(new AxelorException(String.format("Batch d'export des prélèvements %s", paymentScheduleLine.getName()), e, e.getcategory()), IException.DIRECT_DEBIT, batch.getId());
				
				incrementAnomaly();
				
			} catch (Exception e) {
				
				TraceBackService.trace(new Exception(String.format("Batch d'export des prélèvements %s", paymentScheduleLine.getName()), e), IException.DIRECT_DEBIT, batch.getId());
				
				incrementAnomaly();
				
				LOG.error("Bug(Anomalie) généré(e) pour le batch d'export des prélèvements {}", paymentScheduleLine.getName());
				
			} finally {
				
				if (i % 10 == 0) { JPA.clear(); }
	
			}	
			
		}
		return paymentScheduleLineExportedList;
	}
	
	
	
	/**
	 * Procédure permettant d'exporter des factures
	 * @param company
	 * 			Une société
	 * @param pse
	 * 			Un Export des prélèvement
	 * @param pm
	 * 			Un mode de paiement
	 * @throws AxelorException
	 */
	public List<Invoice> exportInvoice(Company company, LocalDate scheduleDate, PaymentMode paymentMode)  {
		
		List<MoveLine> moveLineList = paymentScheduleExportService.getInvoiceToExport(Company.find(company.getId()), PaymentMode.find(paymentMode.getId()),scheduleDate);
				
		List<Invoice> invoiceToExportList = new ArrayList<Invoice>();
		
		int i = 0;
		
		for(MoveLine moveLine : moveLineList)  {
			
			try  {
				
				BigDecimal amountRemaining = MoveLine.find(moveLine.getId()).getAmountRemaining();
			
				Invoice invoice = paymentScheduleExportService.exportInvoice(
						MoveLine.find(moveLine.getId()), 
						moveLineList, 
						Company.find(company.getId()), 
						PaymentMode.find(paymentMode.getId()));
				
				if(invoice != null)  {
					invoiceToExportList.add(invoice);
					updateInvoice(invoice);
					this.totalAmount = this.totalAmount.add(amountRemaining);
					i++;
				}
			
			} catch (AxelorException e) {
				
				TraceBackService.trace(new AxelorException(String.format("Batch d'export des prélèvements %s", MoveLine.find(moveLine.getId()).getInvoice().getInvoiceId()), e, e.getcategory()), IException.DIRECT_DEBIT, batch.getId());
				
				incrementAnomaly();
				
			} catch (Exception e) {
				
				TraceBackService.trace(new Exception(String.format("Batch d'export des prélèvements %s", MoveLine.find(moveLine.getId()).getInvoice().getInvoiceId()), e), IException.DIRECT_DEBIT, batch.getId());
				
				incrementAnomaly();
				
				LOG.error("Bug(Anomalie) généré(e) pour le batch d'export des prélèvements {}", MoveLine.find(moveLine.getId()).getInvoice().getInvoiceId());
				
			} finally {
				
				if (i % 10 == 0) { JPA.clear(); }
	
			}
			
		}
		
		return invoiceToExportList;
	}

	
	
	/**
	 * As {@code batch} entity can be detached from the session, call {@code Batch.find()} get the entity in the persistant context.
	 * Warning : {@code batch} entity have to be saved before.
	 */
	@Override
	protected void stop() {
		String comment = ""; 
		
		
		switch (Batch.find(batch.getId()).getAccountingBatch().getDirectDebitExportTypeSelect()) {
		
			case IAccount.INVOICE_EXPORT:
				comment = "Compte rendu d'export des prélèvements factures et échéances de paiement :\n";
				comment += String.format("\t* %s prélèvements(s) facture(s) et échéance(s) traité(s)\n", batch.getDone());
				comment += String.format("\t* Montant total : %s \n", this.totalAmount);
				comment += String.format("\t* %s anomalie(s)", batch.getAnomaly());
				
				break;
				
			case IAccount.MONTHLY_EXPORT:
	            comment = "Compte rendu d'export des prélèvements de mensualité :\n";
	            comment += String.format("\t* %s prélèvements(s) mensualité(s) traité(s)\n", batch.getDone());
	            comment += String.format("\t* Montant total : %s \n", this.totalAmount);
	            comment += String.format("\t* %s anomalie(s)", batch.getAnomaly());
	            
	            break;	
				
		}
		
		comment += String.format("\t* ------------------------------- \n");
		comment += String.format("\t* %s ", updateCustomerAccountLog);
		
		addComment(comment);
		super.stop();
	}

}
