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
package com.axelor.apps.account.service.debtrecovery;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.joda.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.account.db.AccountingSituation;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.PaymentScheduleLine;
import com.axelor.apps.account.db.Reminder;
import com.axelor.apps.account.service.AccountCustomerService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.service.administration.GeneralService;
import com.axelor.apps.tool.date.DateTool;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.IException;
import com.axelor.exception.service.TraceBackService;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class ReminderService {
	
	private static final Logger LOG = LoggerFactory.getLogger(ReminderService.class); 
	
	@Inject
	private ReminderSessionService rss;
	@Inject
	private ReminderActionService ras;
	@Inject
	private AccountCustomerService acs;

	private LocalDate today;

	@Inject
	public ReminderService() {

		this.today = GeneralService.getTodayDate();
		
	}
	
	/******************************************  1. Calcul du solde exigible du contrat  *****************************************************************/
    /** Montant Total des échéances (des factures et des échéanciers) échues (date du jour >= date de l’échéance) - Montant Total payé sur l’échéancier **/
	
	
	
	/******************************************  2. Calcul du solde exigible (relançable) du contrat  ******************************************/
	/** solde des factures exigibles non bloquées en relance et dont « la date de facture » + « délai d’acheminement(X) » <« date du jour »  ***/
	/** solde des échéanciers dont le type est non contentieux et qui ne sont pas bloqués ******************************************************/

	/**
	 * Fonction permettant de calculer le solde exigible relançable d'un contrat
	 * @param contractLine
	 * 			Un contrat
	 * @return
	 * 			Le solde exigible relançable
	 */
	public BigDecimal getBalanceDueReminder(List<MoveLine> moveLineList, Partner partner)  {
		BigDecimal balanceSubstract = this.getSubstractBalanceDue(partner);
		BigDecimal balanceDueReminder = BigDecimal.ZERO;
		for(MoveLine moveLine : moveLineList)  {
			balanceDueReminder = balanceDueReminder.add(moveLine.getAmountRemaining());
		}
		balanceDueReminder = balanceDueReminder.add(balanceSubstract);
		return balanceDueReminder;
	}
				
	
	public BigDecimal getSubstractBalanceDue( Partner partner)  {
		List<MoveLine> moveLineQuery = MoveLine.all().filter("self.partner = ?1", partner).fetch();
		BigDecimal balance = BigDecimal.ZERO;
		for(MoveLine moveLine : moveLineQuery)  {
			if(moveLine.getCredit().compareTo(BigDecimal.ZERO) > 0)  {
				if(moveLine.getAccount()!=null && moveLine.getAccount().getReconcileOk() && !moveLine.getIgnoreInReminderOk() && !(moveLine.getFromSchedulePaymentOk() && moveLine.getIgnoreInAccountingOk()))  {
					balance = balance.subtract(moveLine.getAmountRemaining());
				}
			}
		}
		return balance;
	}
	
			
	/******************************************  3. Calcul de la date de référence  ******************************************/
	

	/**
	 * Fonction qui récupère la plus ancienne date d'échéance d'une liste de lignes d'écriture
	 * @param moveLineList
	 * 			Une liste de lignes d'écriture
	 * @return 
	 * 			la plus ancienne date d'échéance
	 */
	public LocalDate getOldDateMoveLine(List<MoveLine> moveLineList)  {
		LocalDate minMoveLineDate = new LocalDate();
		
		
		if(moveLineList != null && !moveLineList.isEmpty())  {
			for(MoveLine moveLine : moveLineList)  {
				if(minMoveLineDate.isAfter(moveLine.getDueDate()))  {	minMoveLineDate=moveLine.getDueDate();	}
			}
		}
		else  {	minMoveLineDate=null;	}
		return minMoveLineDate;
	}
	
	
	/**
	 * Fonction qui récupére la plus récente date entre deux date
	 * @param date1
	 * 			Une date
	 * @param date2
	 * 			Une date
	 * @return minDate
	 * 			La plus ancienne date
	 */
	public LocalDate getLastDate(LocalDate date1, LocalDate date2)  {
		LocalDate minDate = new LocalDate();
		if(date1!=null && date2!=null)  {
			if(date1.isAfter(date2))  {	minDate=date1;	}
			else  {  minDate=date2;  }
		}
		else if(date1!=null) {	minDate=date1;	}
		else if(date2!=null)  {	minDate=date2;	}
		else  {	minDate=null;	}
		return minDate;
	}
	
	
	/**
	 * Fonction qui permet de récupérer la date de relance la plus récente
	 * @param contractLine
	 * 			Un contrat
	 * @param isLitigation
	 * 			Le contrat est-il en contentieux?
	 * @return 
	 * 			La date de relance la plus récente
	 */
	public LocalDate getLastDateReminder(Reminder reminder)  {
		return reminder.getReminderDate(); 
	}
	
	
	/**
	 * Fonction qui détermine la date de référence
	 * @param contractLine
	 * 			Un contrat
	 * @param isLitigation 
	 * 			Le contrat est-il en contentieux?
	 * @return
	 * 			La date de référence
	 */
	public LocalDate getReferenceDate(Reminder reminder)  {
		AccountingSituation accountingSituation = reminder.getAccountingSituation();
		List<MoveLine> moveLineList = this.getMoveLineReminder(accountingSituation.getPartner(), accountingSituation.getCompany());
		
		// Date la plus ancienne des lignes d'écriture
		LocalDate minMoveLineDate = getOldDateMoveLine(moveLineList);
		LOG.debug("minMoveLineDate : {}",minMoveLineDate);	
		
		// 2: Date la plus récente des relances
		LocalDate reminderLastDate = getLastDateReminder(reminder);
		LOG.debug("reminderLastDate : {}",reminderLastDate);
		
		// Date de référence : Date la plus récente des deux ensembles (1 et 2)
		LocalDate reminderRefDate = getLastDate(minMoveLineDate, reminderLastDate);
		LOG.debug("reminderRefDate : {}",reminderRefDate);	
		
		return reminderRefDate;
	}
	
	
/******************************************  Méthodes communes 1,2 et 3  ******************************************/
	
	
	/**
	 * Fonction permettant de récuperer une liste de ligne d'écriture exigible relançable d'un contrat
	 * @param contractLine
	 * 			Un contrat
	 * @return
	 * 			La liste de ligne d'écriture
	 */
	public List<MoveLine> getMoveLineReminder(Partner partner, Company company)  {
		List<MoveLine> moveLineList = new ArrayList<MoveLine>();

		List<MoveLine> moveLineQuery = this.getMoveLine(partner, company);
		
		for(MoveLine moveLine : moveLineQuery)  {
			if(moveLine.getMove()!=null)  {
				Move move = moveLine.getMove();
				//facture exigibles non bloquée en relance et dont la date de facture + délai d'acheminement < date du jour
				if(move.getInvoice()!=null && !move.getInvoice().getReminderBlockingOk() 
						&& !move.getInvoice().getSchedulePaymentOk()
						&& ((move.getInvoice().getInvoiceDate()).plusDays(company.getMailTransitTime())).isBefore(today))  {
					if((moveLine.getDebit().compareTo(BigDecimal.ZERO) > 0) 
							&& moveLine.getDueDate() != null
							&&	(today.isAfter(moveLine.getDueDate())  || today.isEqual(moveLine.getDueDate())))  {
						if(moveLine.getAccount()!=null && moveLine.getAccount().getReconcileOk() && !moveLine.getIgnoreInReminderOk())  {
							if(moveLine.getAmountRemaining().compareTo(BigDecimal.ZERO) > 0)  {
								moveLineList.add(moveLine);
							}
						}
					}
				}
				//échéanciers dont le type est non contentieux et qui ne sont pas bloqués
				else if(move.getInvoice()==null)  {
					if((moveLine.getDebit().compareTo(BigDecimal.ZERO) > 0) 
							&& moveLine.getDueDate() != null
							&&	(today.isAfter(moveLine.getDueDate())  || today.isEqual(moveLine.getDueDate())))  {
						if(moveLine.getAccount()!=null && moveLine.getAccount().getReconcileOk() && !moveLine.getIgnoreInReminderOk())  {
							if((moveLine.getFromSchedulePaymentOk() && moveLine.getIgnoreInAccountingOk()) 
									|| (!moveLine.getFromSchedulePaymentOk() && !moveLine.getIgnoreInAccountingOk()))  { 
								if(moveLine.getAmountRemaining().compareTo(BigDecimal.ZERO) > 0)  {
									moveLineList.add(moveLine);
								}
							}
						}
					}
				}
			}
		}	
		return moveLineList;
	}
	
	
	public List<Invoice> getInvoiceList(List<MoveLine> moveLineList)  {
		List<Invoice> invoiceList = new ArrayList<Invoice>();
		for(MoveLine moveLine : moveLineList)  {
			if(moveLine.getMove().getInvoice()!=null && !moveLine.getMove().getInvoice().getReminderBlockingOk() )  {
				invoiceList.add(moveLine.getMove().getInvoice());
			}
		}
		return invoiceList;
	}
	
	
	public List<PaymentScheduleLine> getPaymentScheduleList(List<MoveLine> moveLineList, Partner partner)  {
		List<PaymentScheduleLine> paymentScheduleLineList = new ArrayList<PaymentScheduleLine>();
		for(MoveLine moveLine : moveLineList)  {
			if(moveLine.getMove().getInvoice()==null )  {
				// Ajout à la liste des échéances exigibles relançables
				PaymentScheduleLine psl = getPaymentScheduleFromMoveLine(partner, moveLine);
				if(psl != null)  {
					// Si le status est 'cloturé' et un montant restant à payer, c'est à dire des échéances d'un échéancier annulé
					if(psl.getAmountRemaining().compareTo(BigDecimal.ZERO) == 0 || !psl.getStatus().getCode().equals("clo"))  {
						paymentScheduleLineList.add(psl);
					}
				}
			}
		}
		return paymentScheduleLineList;
	}
	
	/**
	 * Méthode permettant de récupérer l'ensemble des lignes d'écriture d'un contrat
	 * @param contractLine
	 * @return
	 */
	public List<MoveLine> getMoveLine(Partner partner, Company company)  {
		
		return MoveLine.all().filter("self.partner = ?1 and self.move.company = ?2", partner, company).fetch();

	}
	
	
	/**
	 * Méthode permettant de récupérer une ligne d'échéancier depuis une ligne d'écriture
	 * @param contractLine
	 * @param moveLine
	 * @return
	 */
	public PaymentScheduleLine getPaymentScheduleFromMoveLine(Partner partner, MoveLine moveLine)  {
//		for(PaymentSchedule paymentSchedule : partner.getPaymentScheduleList())  {
//			for(PaymentScheduleLine paymentScheduleLine : paymentSchedule.getPaymentScheduleLineList())  {
//				if(moveLine.equals(paymentScheduleLine.getMoveLineGenerated()))  {
//					return paymentScheduleLine;
//				}
//			}		
//		}
//		return null;
		return moveLine.getPaymentScheduleLine();
	}
	

	/**
	 * Procédure permettant de tester si aujourd'hui nous sommes dans une période particulière
	 * @param dayBegin
	 * 			Le jour du début de la période
	 * @param dayEnd
	 * 			Le jour de fin de la période
	 * @param monthBegin
	 * 			Le mois de début de la période
	 * @param monthEnd
	 * 			Le mois de fin de la période
	 * @return
	 * 			Sommes-nous dans la période?
	 */
	public boolean periodOk(int dayBegin, int dayEnd, int monthBegin, int monthEnd)  {
		
		return DateTool.dateInPeriod(today, dayBegin, monthBegin, dayEnd, monthEnd);
		
	}
	
	
	
	/**
	 * Méthode de relance en masse
	 * @param company 
	 * 		Une société
	 */
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public void reminderAllContractLine(Company company)   {

		int i = 0;
		List<Partner> allPartners = Partner.all().filter("self.reminderClosedOk = false AND self.contract.company = ?1", company).fetch();

		for (Partner partner : allPartners) {

			i += 1;

			// Commit
			try {
				
				this.reminder(partner, company);
				LOG.debug("Tiers traité : {}", partner.getName());	
				if (i % 50 == 0)  {
					JPA.flush();
				}

			} catch (Exception e) {
				TraceBackService.trace(e);
				LOG.error("Bug(Anomalie) généré(e) pour le Tiers {}",partner.getName());
			}

		}

	}
	

	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public boolean reminder(Partner partner, Company company) throws AxelorException  {
		boolean remindedOk = this.reminderGenerate(partner, company);
		partner.save();
		return remindedOk;
	}
	
	
	
	public Reminder getReminder(Partner partner, Company company) throws AxelorException  {
		AccountingSituation accountingSituation = AccountingSituation.all().filter("self.partner = ?1 and self.company = ?2", partner, company).fetchOne();
		if(accountingSituation != null)  {
			if(accountingSituation.getReminder() != null)  {
				return accountingSituation.getReminder();
			}
			else  {
				return this.createReminder(accountingSituation);
			}
		}
		
		else  {
			throw new AxelorException(String.format("%s :\nTiers %s, Société %s : Pas de situation comptable.", 
					GeneralService.getExceptionReminderMsg(), partner.getName(), company.getName()), IException.CONFIGURATION_ERROR);
		}
	}
	
	
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public Reminder createReminder(AccountingSituation accountingSituation)  {
		Reminder reminder = new Reminder();
		reminder.setAccountingSituation(accountingSituation);
		reminder.save();
		return reminder;
	}
	
	
	/**
	 * Méthode de relance en masse
	 * @param contractLine
	 * 			Un contrat
	 * @param period1Ok
	 * 			Est-on dans la période de blocage en relance pour les contrats sociaux
	 * @param period2Ok
	 * 			Est-on dans la période d'envoie de mail automatique pour les contrats sociaux bloqués en relance de niveau 3
	 * @throws AxelorException
	 */
	@Transactional(rollbackOn = {AxelorException.class, Exception.class})
	public boolean reminderGenerate(Partner partner, Company company) throws AxelorException {			
		boolean remindedOk = false;
			
		BigDecimal balanceDue = acs.getBalanceDue(partner, company);
		
		Reminder reminder = this.getReminder(partner, company); // ou getReminder si existe
		
		if (balanceDue.compareTo(BigDecimal.ZERO) > 0)  {
			
			reminder.setBalanceDue(balanceDue);
			LOG.debug("balanceDue : {} ",balanceDue);
			
			List<MoveLine> moveLineList = this.getMoveLineReminder(partner, company);
			BigDecimal balanceDueReminder = this.getBalanceDueReminder(moveLineList, partner);
			
			if (balanceDueReminder.compareTo(BigDecimal.ZERO) > 0) {
				LOG.debug("balanceDueReminder : {} ",balanceDueReminder);
				
				remindedOk = true;
				
				this.updateInvoiceReminder(reminder,  this.getInvoiceList(moveLineList));
				this.updatePaymentScheduleLineReminder(reminder, this.getPaymentScheduleList(moveLineList, partner));
				
				reminder.setBalanceDueReminder(balanceDueReminder);
				
				Integer levelReminder = 0;
				if(reminder.getReminderMethodLine() != null)  {
					levelReminder = reminder.getReminderMethodLine().getReminderLevel().getName();
				}
				
				LocalDate referenceDate = this.getReferenceDate(reminder);
				
				if(referenceDate != null)  {
					LOG.debug("date de référence : {} ",referenceDate);
					reminder.setReferenceDate(referenceDate);	
				}
				else {
					throw new AxelorException(String.format("%s :\nTiers %s, Société %s : Date de reference non determinée.", 
							GeneralService.getExceptionReminderMsg(), partner.getName(), company.getName()), IException.CONFIGURATION_ERROR);
				}
				if(reminder.getReminderMethod() == null)  {
					if(rss.getReminderMethod(reminder)!=null)  {
						reminder.setReminderMethod(rss.getReminderMethod(reminder));
						rss.reminderSession(reminder);
					}
					else  {
						throw new AxelorException(String.format("%s :\nTiers %s, Société %s : Méthode de relance absente pour la configuration", 
							GeneralService.getExceptionReminderMsg(), partner.getName(), company.getName()), IException.CONFIGURATION_ERROR);
					}
				}
				else {
					rss.reminderSession(reminder);
				}
				if(reminder.getWaitReminderMethodLine()==null)  {
					// Si le niveau de relance à évolué
					if(reminder.getReminderMethodLine() != null && reminder.getReminderMethodLine().getReminderLevel() != null &&
							reminder.getReminderMethodLine().getReminderLevel().getName() > levelReminder)  {
						ras.runAction(reminder);
					}
				}
				else  {
					LOG.debug("Tiers {}, Société {} - Niveau de relance en attente ", partner.getName(), company.getName());	
					// TODO Alarm ?
					TraceBackService.trace(new AxelorException(
						String.format("%s :\nTiers %s, Société %s : Niveau de relance en attente de validation", 
								GeneralService.getExceptionReminderMsg(), partner.getName(), company.getName()), IException.INCONSISTENCY));
				}	
			}
		}
		else  {
			rss.reminderInitialisation(reminder);
		}
		return remindedOk;
	}
	
	
	public void updateInvoiceReminder(Reminder reminder, List<Invoice> invoiceList)  {
		reminder.setInvoiceReminderSet(new HashSet<Invoice>());
		reminder.getInvoiceReminderSet().addAll(invoiceList);
	}
	
	public void updatePaymentScheduleLineReminder(Reminder reminder, List<PaymentScheduleLine> paymentSchedueLineList)  {
		reminder.setPaymentScheduleLineReminderSet(new HashSet<PaymentScheduleLine>());
		reminder.getPaymentScheduleLineReminderSet().addAll(paymentSchedueLineList);
	}
	
}