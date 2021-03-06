package ceid.netcins.exo.similarity;

/**
 * Abstract interface which defines in general the Similarity measurement.
 * Subclasses implement search scoring functions.
 *
 * @author <a href="mailto:loupasak@ceid.upatras.gr">Andreas Loupasakis</a>
 * @author <a href="mailto:ntarmos@cs.uoi.gr">Nikos Ntarmos</a>
 * @author <a href="mailto:peter@ceid.upatras.gr">Peter Triantafillou</a>
 *         <p/>
 *         "eXO: Decentralized Autonomous Scalable Social Networking"
 *         Proc. 5th Biennial Conf. on Innovative Data Systems Research (CIDR),
 *         January 9-12, 2011, Asilomar, California, USA.
 * @version 1.0
 */
public abstract interface Similarity {

    /**
     * This is the function that summarizes all the functionality of similarity
     * matching. It computes the total score from the previous level factors
     * multiplied with the appropriate weights.
     *
     * @return The total score of the similarity between document and query.
     */
    public abstract float getScore();

    /**
     * This function returns the previous level factors of scoring computation
     *
     * @return
     */
    public abstract Object[] getSimilarityFactors();

}
