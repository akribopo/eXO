package ceid.netcins.frontend.handlers;

import java.util.Hashtable;
import java.util.Vector;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import rice.Continuation;
import ceid.netcins.CatalogService;
import ceid.netcins.messages.ResponsePDU;

/**
 * 
 * @author <a href="mailto:loupasak@ceid.upatras.gr">Andreas Loupasakis</a>
 * @author <a href="mailto:ntarmos@cs.uoi.gr">Nikos Ntarmos</a>
 * @author <a href="mailto:peter@ceid.upatras.gr">Peter Triantafillou</a>
 * 
 * "eXO: Decentralized Autonomous Scalable Social Networking"
 * Proc. 5th Biennial Conf. on Innovative Data Systems Research (CIDR),
 * January 9-12, 2011, Asilomar, California, USA.
 * 
 */
public class SearchContentDHTHandler extends AbstractHandler {
	private static final long serialVersionUID = 825367464625718048L;

	public SearchContentDHTHandler(CatalogService catalogService,
			Hashtable<String, Vector<Object>> queue) {
		super(catalogService, queue);
	}

	@Override
	public void doPost(HttpServletRequest request,
			HttpServletResponse response) throws ServletException {
		if (prepare(request, response) == RequestState.FINISHED)
			return;

		if (rawQuery == null || queryTopK == null) {
			sendStatus(response, RequestStatus.FAILURE, null);
			return;
		}

		final String reqID = getNewReqID(response);
		catalogService.searchContent(rawQuery, queryTopK, new Continuation<Object, Exception>() {
			@Override
			public void receiveResult(Object arg0) {
				if (arg0 == null || !(arg0 instanceof ResponsePDU)) {
					queueStatus(reqID, RequestStatus.FAILURE, null);
					return;
				}
				queueStatus(reqID, RequestStatus.SUCCESS, ((ResponsePDU)arg0).getScoreBoard());
			}

			@Override
			public void receiveException(Exception arg0) {
				queueStatus(reqID, RequestStatus.FAILURE, null);
			}
		});
	}
}
