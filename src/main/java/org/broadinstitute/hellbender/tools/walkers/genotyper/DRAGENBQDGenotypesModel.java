package org.broadinstitute.hellbender.tools.walkers.genotyper;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.GenotypeLikelihoods;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.genotyper.AlleleList;
import org.broadinstitute.hellbender.utils.genotyper.AlleleListPermutation;
import org.broadinstitute.hellbender.utils.genotyper.LikelihoodMatrix;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.ReadUtils;

import java.util.ArrayList;
import java.util.List;

public class DRAGENBQDGenotypesModel extends GenotypersModel {
    private static final int DEFAULT_CACHE_PLOIDY_CAPACITY = 10;
    private static final int DEFAULT_CACHE_ALLELE_CAPACITY = 50;

    private final int cacheAlleleCountCapacity;
    private final int cachePloidyCapacity;
    private GenotypeLikelihoodCalculator[][] likelihoodCalculators;
    private final GenotypeLikelihoodCalculators calculators;

    // We keep a fallback model in mind... this might want to be adjusted as implementation workds
    private final IndependentSampleGenotypesModel fallbackModel;

    public DRAGENBQDGenotypesModel() { this(DEFAULT_CACHE_PLOIDY_CAPACITY, DEFAULT_CACHE_ALLELE_CAPACITY); }

    /**
     *  Initialize model with given maximum allele count and ploidy for caching
     */
    public DRAGENBQDGenotypesModel(final int calculatorCachePloidyCapacity, final int calculatorCacheAlleleCapacity) {
        fallbackModel = new IndependentSampleGenotypesModel(calculatorCachePloidyCapacity, calculatorCacheAlleleCapacity);
        cachePloidyCapacity = calculatorCachePloidyCapacity;
        cacheAlleleCountCapacity = calculatorCacheAlleleCapacity;
        likelihoodCalculators = new GenotypeLikelihoodCalculator[calculatorCachePloidyCapacity][calculatorCacheAlleleCapacity];
        calculators = new GenotypeLikelihoodCalculators();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <A extends Allele> GenotypingLikelihoods<A> calculateLikelihoods(final AlleleList<A> genotypingAlleles, final GenotypingData<A> data) {
        Utils.nonNull(genotypingAlleles, "the allele cannot be null");
        Utils.nonNull(data, "the genotyping data cannot be null");

        // for right now, don't handle any deletions whatsoever
        if (FRDBQDUtils.containsInsertionOrDeletion(genotypingAlleles)) {
            return fallbackModel.calculateLikelihoods(genotypingAlleles, data);
        }

        data.readLikelihoods()

        final AlleleListPermutation<A> permutation = data.permutation(genotypingAlleles);
        final AlleleLikelihoodMatrixMapper<A> alleleLikelihoodMatrixMapper = new AlleleLikelihoodMatrixMapper(permutation);

        final int sampleCount = data.numberOfSamples();
        final PloidyModel ploidyModel = data.ploidyModel();
        final List<GenotypeLikelihoods> genotypeLikelihoods = new ArrayList<>(sampleCount);
        final int alleleCount = genotypingAlleles.numberOfAlleles();

        GenotypeLikelihoodCalculator likelihoodsCalculator = sampleCount > 0 ? getLikelihoodsCalculator(ploidyModel.samplePloidy(0), alleleCount) : null;
        for (int i = 0; i < sampleCount; i++) {

            // Separating the reads by their strand and sorting them appropriately.
            List<GATKRead> readsForSample = data.readLikelihoods().sampleEvidence(i);
            List<Pair<GATKRead,Integer>> strandForward = new ArrayList<>();
            List<Pair<GATKRead,Integer>>  strandReverse = new ArrayList<>();
            for (int j = 0; i < readsForSample.size(); i++) {
                if (readsForSample.get(j).isReverseStrand()) {
                    strandReverse.add(Pair.of(readsForSample.get(j), j));
                } else {
                    strandForward.add(Pair.of(readsForSample.get(j), j));
                }
            }
            ReadUtils.getReadCoordinateForReferenceCoordinate(strandForward.get(1).getLeft(),data.readLikelihoods().getSubsettedGenomicLoc().getStart());
            strandForward.sort(FRDBQDUtils.ReadFeatherEndForwardComparitor(data.readLikelihoods().getSubsettedGenomicLoc()));


            final int samplePloidy = ploidyModel.samplePloidy(i);

            // get a new likelihoodsCalculator if this sample's ploidy differs from the previous sample's
            if (samplePloidy != likelihoodsCalculator.ploidy()) {
                likelihoodsCalculator = getLikelihoodsCalculator(samplePloidy, alleleCount);
            }

            final LikelihoodMatrix<GATKRead, A> sampleLikelihoods = alleleLikelihoodMatrixMapper.mapAlleles(data.readLikelihoods().sampleMatrix(i));
            genotypeLikelihoods.add(likelihoodsCalculator.genotypeLikelihoods(sampleLikelihoods));
        }
        return new GenotypingLikelihoods<>(genotypingAlleles, ploidyModel, genotypeLikelihoods);
    }

    private GenotypeLikelihoodCalculator getLikelihoodsCalculator(final int samplePloidy, final int alleleCount) {
        if (samplePloidy >= cachePloidyCapacity || alleleCount >= cacheAlleleCountCapacity) {
            return calculators.getInstance(samplePloidy, alleleCount);
        }
        final GenotypeLikelihoodCalculator result = likelihoodCalculators[samplePloidy][alleleCount];
        if (result != null) {
            return result;
        } else {
            final GenotypeLikelihoodCalculator newOne = calculators.getInstance(samplePloidy, alleleCount);
            likelihoodCalculators[samplePloidy][alleleCount] = newOne;
            return newOne;
        }
    }
}