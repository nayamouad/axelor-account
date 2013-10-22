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
package com.axelor.apps.account.service.payment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.persistence.Query;

import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Reconcile;
import com.axelor.apps.account.service.MoveLineService;
import com.axelor.apps.account.service.MoveService;
import com.axelor.apps.account.service.PaymentScheduleService;
import com.axelor.apps.account.service.ReconcileService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.service.administration.GeneralService;
import com.axelor.apps.account.db.PaymentInvoiceToPay;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.google.inject.Inject;

public class PaymentService {

	private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);
	
	@Inject
	private ReconcileService rcs;

	@Inject
	private PaymentScheduleService pss;
	
	@Inject
	private MoveLineService mls;
	
	@Inject
	private ReconcileService rs;
	
	@Inject
	private MoveService ms;
	
	private LocalDate date;
	
	@Inject
	public PaymentService(){
	
		date = GeneralService.getTodayDate();
		
	}
	
	
	/**
	 * Employer le trop perçu d'une facture.
	 * Les trop-perçus récupérés sont ceux appartenant au même contrat que la facture
	 *
	 * @param invoice
	 * @param move
	 * @throws AxelorException
	 */
	@Deprecated
	public void useExcessPaymentOnMoveLines(Invoice invoice, Move move) throws AxelorException {
		 
		LOG.debug("In useExcessPaymentOnMoveLines");		

		 // Récupérer la ligne en débit de la facture
		 MoveLine invoiceLineDebit = null;
		 for (MoveLine moveLineDebit : move.getMoveLineList()){
			 if (moveLineDebit.getDebit().compareTo(BigDecimal.ZERO) == 1 && moveLineDebit.getPartner().equals(invoice.getClientPartner()) &&
				 moveLineDebit.getAmountRemaining().compareTo(BigDecimal.ZERO) == 1){
				 invoiceLineDebit = moveLineDebit;
				 break;
			 }
		 }
		
		 if(invoiceLineDebit != null) {
		
			 LOG.debug("Comptes : {}", invoiceLineDebit.getAccount());
			
			 // Récupérer les trop pérçu du tiers pour le même compte que la ligne en débit
			 List<MoveLine> creditMoveLines = this.getExcessPayment(invoice, invoiceLineDebit.getAccount());
			
			 List<MoveLine> debitMoveLines = new ArrayList<MoveLine>();
			 debitMoveLines.add(invoiceLineDebit);
			
			 LOG.debug("creditMoveLines : {}",creditMoveLines);		
			 LOG.debug("debitMoveLines : {}",debitMoveLines);

			 if (debitMoveLines != null) {
				 this.useExcessPaymentOnMoveLines(debitMoveLines,creditMoveLines);
			 }
		 }
	}
	
	
	/**
	 * Méthode permettant de récupérer les trop-perçus pour un compte donné (411) et une facture
	 * @param invoice
	 * 			Une facture
	 * @param account
	 * 			Un compte
	 * @return
	 * @throws AxelorException
	 */
	public List<MoveLine> getExcessPayment(Invoice invoice, Account account) throws AxelorException {
		 Company company = invoice.getCompany();
		
		 List<MoveLine> creditMoveLines =  MoveLine
		 .all()
		 .filter("move.company = ?1 and move.state = ?2 and fromSchedulePaymentOk = false" +
		 " and account.reconcileOk = ?3 and credit > 0 and amountRemaining > 0 " +
		 "and partner = ?4 and account = ?5 ORDER by date asc ",
		 company, "validated", true, invoice.getClientPartner(), account).fetch();
		 
		 LOG.debug("Nombre de trop-perçus à imputer sur la facture récupéré : {}", creditMoveLines.size());

		 return creditMoveLines;
	}
	
	
	public List<MoveLine> getInvoiceDue(Invoice invoice, boolean useOthersInvoiceDue) throws AxelorException {
		Company company = invoice.getCompany();
		Partner partner = invoice.getClientPartner();

		// Récupérer les dûs du tiers pour le même compte que celui de l'avoir
		List<MoveLine> debitMoveLines = ms.getOrignalInvoiceFromRefund(invoice);
			
		List<MoveLine> othersDebitMoveLines = null;
		if(useOthersInvoiceDue)  {
			if(debitMoveLines != null && debitMoveLines.size() != 0)  {
				othersDebitMoveLines = MoveLine
						 .all().filter("move.company = ?1 and move.state = ?2 and fromSchedulePaymentOk = false" +
						 " and account.reconcileOk = ?3 and debit > 0 and amountRemaining > 0 " +
						 "and partner = ?4 and self NOT IN ?5 ORDER by date asc ",
						 company, "validated", true, partner, debitMoveLines).fetch();
			}
			else  {
				othersDebitMoveLines = MoveLine
						 .all().filter("move.company = ?1 and move.state = ?2 and fromSchedulePaymentOk = false" +
						 " and account.reconcileOk = ?3 and debit > 0 and amountRemaining > 0 " +
						 "and partner = ?4 ORDER by date asc ",
						 company, "validated", true, partner).fetch();
			}
			debitMoveLines.addAll(othersDebitMoveLines);
		}
		
		LOG.debug("Nombre de ligne à payer avec l'avoir récupéré : {}", debitMoveLines.size());
		 
		return debitMoveLines;
	}
	
	
	/**
	 * Utiliser le trop perçu entre deux listes de lignes d'écritures (une en débit, une en crédit)
	 * Si cette methode doit être utilisée, penser à ordonner les listes qui lui sont passées par date croissante
	 * Ceci permet de payer les facture de manière chronologique.
	 * 
	 * @param debitMoveLines = dûs
	 * @param creditMoveLines = trop-perçu
	 * 
	 * @return
	 * @throws AxelorException 
	 */
	public void useExcessPaymentOnMoveLines(List<MoveLine> debitMoveLines, List<MoveLine> creditMoveLines) throws AxelorException {
		
		this.useExcessPaymentOnMoveLines(debitMoveLines, creditMoveLines, true);
		
	}
	

	/**
	 * Utiliser le trop perçu entre deux listes de lignes d'écritures (une en débit, une en crédit)
	 * Si cette methode doit être utilisée, penser à ordonner les listes qui lui sont passées par date croissante
	 * Ceci permet de payer les facture de manière chronologique.
	 * 
	 * @param debitMoveLines = dûs
	 * @param creditMoveLines = trop-perçu
	 * 
	 * @return
	 * @throws AxelorException 
	 */
	public void useExcessPaymentOnMoveLines(List<MoveLine> debitMoveLines, List<MoveLine> creditMoveLines, boolean updateCustomerAccount) throws AxelorException {
		
		if(debitMoveLines != null && creditMoveLines != null){

			LOG.debug("Emploie du trop perçu (nombre de lignes en débit : {}, nombre de ligne en crédit : {})",
				new Object[]{debitMoveLines.size(), creditMoveLines.size()});
			
			BigDecimal amount = null;
			Reconcile reconcile = null;
			
			BigDecimal debitTotalRemaining = BigDecimal.ZERO;
			BigDecimal creditTotalRemaining = BigDecimal.ZERO;
			for(MoveLine creditMoveLine : creditMoveLines)  {
				creditTotalRemaining = creditTotalRemaining.add(creditMoveLine.getAmountRemaining());
			}
			for(MoveLine debitMoveLine : debitMoveLines)  {
				debitTotalRemaining=debitTotalRemaining.add(debitMoveLine.getAmountRemaining());
			}
			
			for(MoveLine creditMoveLine : creditMoveLines){
				
				if (creditMoveLine.getAmountRemaining().compareTo(BigDecimal.ZERO) == 1) {
					
					for(MoveLine debitMoveLine : debitMoveLines){
						if ((debitMoveLine.getAmountRemaining().compareTo(BigDecimal.ZERO) == 1) && (creditMoveLine.getAmountRemaining().compareTo(BigDecimal.ZERO) == 1)) {
					
							if(debitMoveLine.getMaxAmountToReconcile() != null && debitMoveLine.getMaxAmountToReconcile().compareTo(BigDecimal.ZERO) > 0)  {
								amount = debitMoveLine.getMaxAmountToReconcile().min(creditMoveLine.getAmountRemaining());
								debitMoveLine.setMaxAmountToReconcile(null);
							}
							else  {
								amount = creditMoveLine.getAmountRemaining().min(debitMoveLine.getAmountRemaining());
							}
							LOG.debug("amount : {}",amount);
							LOG.debug("debitTotalRemaining : {}",debitTotalRemaining);
							LOG.debug("creditTotalRemaining : {}",creditTotalRemaining);
							BigDecimal nextDebitTotalRemaining = debitTotalRemaining.subtract(amount);
							BigDecimal nextCreditTotalRemaining = creditTotalRemaining.subtract(amount);
							// Gestion du passage en 580
							if(nextDebitTotalRemaining.compareTo(BigDecimal.ZERO) <= 0 
									|| nextCreditTotalRemaining.compareTo(BigDecimal.ZERO) <= 0)  {						
								LOG.debug("last loop");
								if(creditMoveLine.getPaymentScheduleLine() != null && !pss.isLastSchedule(creditMoveLine.getPaymentScheduleLine()))  {
									reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amount, false, false, false);
								}
								else  {
									reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amount, true, false, false);
								}
							}
							else  {
								reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amount, false, false, false);
							}
							// End gestion du passage en 580
							
							rcs.confirmReconcile(reconcile, updateCustomerAccount);

							debitTotalRemaining= debitTotalRemaining.subtract(amount);
							creditTotalRemaining = creditTotalRemaining.subtract(amount);
							
							LOG.debug("Réconciliation : {}", reconcile);
							
						}
					}
				}
			}
		}
	}
	
	
	/**
	 * Il crée des écritures de trop percu avec des montants exacts pour chaque débitMoveLines 
	 * avec le compte du débitMoveLines.
	 * A la fin, si il reste un trop-percu alors créer un trop-perçu classique.
	 * @param debitMoveLines
	 * 					Les lignes d'écriture à payer
	 * @param remainingPaidAmount
	 * 					Le montant restant à payer
	 * @param move	
	 * 					Une écriture
	 * @param moveLineNo
	 * 					Un numéro de ligne d'écriture
	 * @return 
	 * @throws AxelorException
	 */
	public int createExcessPaymentWithAmount(List<MoveLine> debitMoveLines, BigDecimal remainingPaidAmount, Move move, int moveLineNo, Partner partner,
			Company company, PaymentInvoiceToPay paymentInvoiceToPay, Account account, LocalDate paymentDate) throws AxelorException  {

		return this.createExcessPaymentWithAmount(debitMoveLines, remainingPaidAmount, move, moveLineNo, partner, company, paymentInvoiceToPay, account, paymentDate, true);
	}
	
	
	/**
	 * Il crée des écritures de trop percu avec des montants exacts pour chaque débitMoveLines 
	 * avec le compte du débitMoveLines.
	 * A la fin, si il reste un trop-percu alors créer un trop-perçu classique.
	 * @param debitMoveLines
	 * 					Les lignes d'écriture à payer
	 * @param remainingPaidAmount
	 * 					Le montant restant à payer
	 * @param move	
	 * 					Une écriture
	 * @param moveLineNo
	 * 					Un numéro de ligne d'écriture
	 * @return 
	 * @throws AxelorException
	 */
	public int createExcessPaymentWithAmount(List<MoveLine> debitMoveLines, BigDecimal remainingPaidAmount, Move move, int moveLineNo, Partner partner,
			Company company, PaymentInvoiceToPay paymentInvoiceToPay, Account account, LocalDate paymentDate, boolean updateCustomerAccount) throws AxelorException  {
		LOG.debug("In createExcessPaymentWithAmount");
		int moveLineNo2 = moveLineNo;
		BigDecimal remainingPaidAmount2 = remainingPaidAmount;
		
		List<Reconcile> reconcileList = new ArrayList<Reconcile>();
		int i = debitMoveLines.size();
		for(MoveLine debitMoveLine : debitMoveLines)  {
			i--;
			BigDecimal amountRemaining = debitMoveLine.getAmountRemaining();
			
			//Afin de pouvoir arrêter si il n'y a plus rien pour payer
			if(remainingPaidAmount2.compareTo(BigDecimal.ZERO) <= 0)  {
				break;
			}
			BigDecimal amountToPay = remainingPaidAmount2.min(amountRemaining);
			
			String invoiceName = "";
			if(debitMoveLine.getMove().getInvoice()!=null)  {
				invoiceName = debitMoveLine.getMove().getInvoice().getInvoiceId();
			}
			else  {
				invoiceName = paymentInvoiceToPay.getPaymentVoucher().getRef();
			}
			
			MoveLine creditMoveLine = mls.createMoveLine(move,
					debitMoveLine.getPartner(),
					debitMoveLine.getAccount(),
					amountToPay,
					false,
					false,
					this.date,
					moveLineNo2,
					false,
					false,
					false,
					invoiceName);
			move.getMoveLineList().add(creditMoveLine);
			
			// Utiliser uniquement dans le cas du paiemnt des échéances lors d'une saisie paiement
			if(paymentInvoiceToPay != null)  {
				creditMoveLine.setPaymentScheduleLine(paymentInvoiceToPay.getMoveLine().getPaymentScheduleLine());
				
				paymentInvoiceToPay.setMoveLineGenerated(creditMoveLine);
			}
			
			moveLineNo2++;
			Reconcile reconcile = null;
			
			// Gestion du passage en 580
			if(i == 0 )  {						
				LOG.debug("last loop");
				if(creditMoveLine.getPaymentScheduleLine() != null && !pss.isLastSchedule(creditMoveLine.getPaymentScheduleLine()))  {
					reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amountToPay, false, false, false);
				}
				else  {
					reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amountToPay, true, false, false);
				}
			}
			else  {
				reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amountToPay, false, false, false);
			}
			// End gestion du passage en 580
			
			reconcileList.add(reconcile);

			remainingPaidAmount2 = remainingPaidAmount2.subtract(amountRemaining);
			
		}
		
		for(Reconcile reconcile : reconcileList)  {
			rs.confirmReconcile(reconcile, updateCustomerAccount);
		}
		
		// Si il y a un restant à payer, alors on crée un trop-perçu.
		if(remainingPaidAmount2.compareTo(BigDecimal.ZERO) > 0 )  {
			
			MoveLine moveLine = mls.createMoveLine(move,
					partner,
					account,
					remainingPaidAmount2,
					false,
					false,
					this.date,
					moveLineNo2,
					false,
					false,
					false,
					null);
			
			move.getMoveLineList().add(moveLine);
			moveLineNo2++;
			// Gestion du passage en 580
			rs.balanceCredit(moveLine, company, updateCustomerAccount);
		}
		LOG.debug("End createExcessPaymentWithAmount");
		return moveLineNo2;
	}
	
	
	@Deprecated
	public int useExcessPaymentWithAmount(List<MoveLine> creditMoveLines, BigDecimal remainingPaidAmount, Move move, int moveLineNo, Partner partner,
			Company company, Account account) throws AxelorException  {
		
		LOG.debug("In useExcessPaymentWithAmount");
		
		int moveLineNo2 = moveLineNo;
		BigDecimal remainingPaidAmount2 = remainingPaidAmount;

		List<Reconcile> reconcileList = new ArrayList<Reconcile>();
		int i = creditMoveLines.size();
		for(MoveLine creditMoveLine : creditMoveLines)  {
			i--;
			BigDecimal amountRemaining = creditMoveLine.getAmountRemaining();
			
			//Afin de pouvoir arrêter si il n'y a plus rien à payer
			if(remainingPaidAmount2.compareTo(BigDecimal.ZERO) <= 0)  {
				break;
			}
			BigDecimal amountToPay = remainingPaidAmount2.min(amountRemaining);
			
			MoveLine debitMoveLine = mls.createMoveLine(move,
					creditMoveLine.getPartner(),
					creditMoveLine.getAccount(),
					amountToPay,
					true,
					false,
					this.date,
					moveLineNo2,
					false,
					false,
					false,
					creditMoveLine.getName());
			move.getMoveLineList().add(debitMoveLine);
			
			moveLineNo2++;
			Reconcile reconcile = null;
			
			// Gestion du passage en 580
			if(i == 0 )  {						
				reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amountToPay, true, false, false);
			}
			else  {
				reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amountToPay, false, false, false);
			}
			// End gestion du passage en 580
			
			reconcileList.add(reconcile);

			remainingPaidAmount2 = remainingPaidAmount2.subtract(amountToPay);
			
		}
		
		for(Reconcile reconcile : reconcileList)  {
			rs.confirmReconcile(reconcile);
		}
		
		// Si il y a un restant à payer, alors on crée un dû.
		if(remainingPaidAmount2.compareTo(BigDecimal.ZERO) > 0 )  {
			
			MoveLine debitmoveLine = mls.createMoveLine(move,
					partner,
					account,
					remainingPaidAmount2,
					true,
					false,
					this.date,
					moveLineNo2,
					false,
					false,
					false,
					null);
			
			move.getMoveLineList().add(debitmoveLine);
			moveLineNo2++;
			// Gestion du passage en 580
			rs.balanceCredit(debitmoveLine, company, true);
		}
		LOG.debug("End useExcessPaymentWithAmount");
		
		return moveLineNo2;
	}
	
	
	
	@SuppressWarnings("unchecked")
	public int useExcessPaymentWithAmountConsolidated(List<MoveLine> creditMoveLines, BigDecimal remainingPaidAmount, Move move, int moveLineNo, Partner partner,
			Company company, Account account, LocalDate date, LocalDate dueDate) throws AxelorException  {
		
		LOG.debug("In useExcessPaymentWithAmount");
		
		int moveLineNo2 = moveLineNo;
		BigDecimal remainingPaidAmount2 = remainingPaidAmount;
		
		List<Reconcile> reconcileList = new ArrayList<Reconcile>();
		int i = creditMoveLines.size();
		
		if(i!=0)  {			
			Query q = JPA.em().createQuery("select new map(ml.account, SUM(ml.amountRemaining)) FROM MoveLine as ml " +
					"WHERE ml in ?1 group by ml.account");
			q.setParameter(1, creditMoveLines);
	
			List<Map<Account,BigDecimal>> allMap = new ArrayList<Map<Account,BigDecimal>>();
			allMap = q.getResultList();
			for(Map<Account,BigDecimal> map : allMap) {			
				Account accountMap = (Account)map.values().toArray()[1];
				BigDecimal amountMap = (BigDecimal)map.values().toArray()[0];
				BigDecimal amountDebit = amountMap.min(remainingPaidAmount2);
				if(amountDebit.compareTo(BigDecimal.ZERO) > 0)  {
					MoveLine debitMoveLine = mls.createMoveLine(move,
							partner,
							accountMap,
							amountDebit,
							true,
							false,
							date,
							dueDate,
							moveLineNo2,
							false,
							false,
							false,
							null);
					move.getMoveLineList().add(debitMoveLine);
					moveLineNo2++;
		
					for(MoveLine creditMoveLine : creditMoveLines)  {
						if(creditMoveLine.getAccount().equals(accountMap))  {
							Reconcile reconcile = null;
							i--;
							
							//Afin de pouvoir arrêter si il n'y a plus rien à payer
							if(amountDebit.compareTo(BigDecimal.ZERO) <= 0)  {
								break;
							}
							
							BigDecimal amountToPay = amountDebit.min(creditMoveLine.getAmountRemaining());
							
							// Gestion du passage en 580
							if(i == 0 )  {						
								reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amountToPay, true, false, false);
							}
							else  {
								reconcile = rcs.createGenericReconcile(debitMoveLine, creditMoveLine, amountToPay, false, false, false);
							}
							// End gestion du passage en 580
							
							remainingPaidAmount2 = remainingPaidAmount2.subtract(amountToPay);
							amountDebit = amountDebit.subtract(amountToPay);
							reconcileList.add(reconcile);
						}
					}
				}
			}
			
			for(Reconcile reconcile : reconcileList)  {
				rs.confirmReconcile(reconcile);
			}
		}
		// Si il y a un restant à payer, alors on crée un dû.
		if(remainingPaidAmount2.compareTo(BigDecimal.ZERO) > 0 )  {
			
			MoveLine debitmoveLine = mls.createMoveLine(move,
					partner,
					account,
					remainingPaidAmount2,
					true,
					false,
					date,
					dueDate,
					moveLineNo2,
					false,
					false,
					false,
					null);
			
			move.getMoveLineList().add(debitmoveLine);
			moveLineNo2++;

		}
		LOG.debug("End useExcessPaymentWithAmount");
		
		return moveLineNo2;
	}
	
}
