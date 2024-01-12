import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom


import kotlin.reflect.full.*
import kotlin.reflect.KParameter
import kotlin.reflect.KFunction
import kotlin.reflect.KType

import kotlin.random.Random

import org.jetbrains.person.PersonTest

typealias Solution = List<Any?>
typealias Generation = List<Solution>

data class NSGAInfo(val objectives: Array<Double>, val index: Int, var rank: Int = 0, var distance: Double = 0.0)

fun getJavaFiles(path: String = "."): List<String> {
    val dir = File(path)
    return dir.walk()
            .filter { it.isFile && it.extension == "java" }
            .map { it.absolutePath }
            .toList()
}

fun printCurrentTime() {
    val currentTime = LocalTime.now()
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    println("Current time: ${currentTime.format(formatter)}")
}

fun getParameterTypes(function: KFunction<*>): List<KType> {
    return function.parameters.filter { it.kind != KParameter.Kind.INSTANCE }.map { it.type }
}

fun parseConfigJsonFromFile(filename: String): Map<String, List<Double>> {
    val content = File(filename).readText()
    val map = mutableMapOf<String, List<Double>>()

    val pairs = content
            .trim().removeSurrounding("{", "}")
            .split(Regex(",(?![^\\[]*\\])")) // Split on commas not inside square brackets
            .map { it.trim().split(Regex(":\\s*")).map { elem -> elem.trim().trim('"') } }

    pairs.forEach { (key, value) ->
        val list = value.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().toDouble() }
        map[key] = list
    }
    return map
}

fun getParameterMetaData(function: KFunction<*>): List<Pair<KType, String?>> {
    return function.parameters.filter { it.kind != KParameter.Kind.INSTANCE }
            .map { it.type to it.name }
}

fun getParameterInfo(metaData: Pair<KType, String?>, config: Map<String, List<Double>>): Pair<KType, List<Double>> {
    val paramName = metaData.second?.toLowerCase()
    for (configKey in config.keys) {
        if (paramName?.contains(configKey) == true) {
            return Pair(metaData.first, config[configKey] ?: listOf())
        }
    }
    return Pair(metaData.first, listOf(0.0, 0.0, 0.0))
}

fun generateValueForType(type: Pair<KType, List<Double>>): Any? {
    return when (type.first.classifier) {
        Int::class -> Random.nextInt(type.second[0].toInt(), type.second[1].toInt())
        Double::class -> Random.nextDouble(type.second[0], type.second[1])
        Boolean::class -> Random.nextBoolean()
        else -> null
    }
}

fun generateParameters(parameterTypes: List<Pair<KType, List<Double>>>, clones: Int): Generation {
    val allParameters = mutableListOf<List<Any?>>()

    for (i in 1..clones) {
        val parametersForClone = parameterTypes.map { generateValueForType(it) }
        allParameters.add(parametersForClone)
    }
    return allParameters
}

fun getNextGenerationSimple(
        generation: Generation,
        fitFunctionValues: List<Double>,
        parameterTypes: List<Pair<KType, List<Double>>>,
        tournamentSize: Int, // (danik) add to command line param
        crossoverProbability: Double // (danik) add to command line param
): Generation {
    val newGeneration = mutableListOf<Solution>()

    while (newGeneration.size < generation.size) {
        val parent1 = selection(generation, fitFunctionValues, tournamentSize)
        val parent2 = selection(generation, fitFunctionValues, tournamentSize)

        val offspring_1 = if (Random.nextDouble() < crossoverProbability) {
            crossover(parent1, parent2)
        } else {
            parent1
        }

        val offspring_2 = if (Random.nextDouble() < crossoverProbability) {
            crossover(parent1, parent2)
        } else {
            parent2
        }

        val mutatedOffspring_1 = mutation(offspring_1, parameterTypes)
        val mutatedOffspring_2 = mutation(offspring_2, parameterTypes)

        newGeneration.add(mutatedOffspring_1)
        newGeneration.add(mutatedOffspring_2)
    }

    return newGeneration
}


fun selection(generation: Generation, fitFunctionValues: List<Double>, numberOfParticipants: Int): Solution {
    // Randomly select participants for the tournament
    val participants = List(numberOfParticipants) { Random.nextInt(generation.size) }

    // Find the participant with the lowest fitness value
    val bestParticipantIndex = participants.minByOrNull { participantIndex -> fitFunctionValues[participantIndex] }
            ?: throw IllegalStateException("Unable to determine the best participant.")

    return generation[bestParticipantIndex]
}

fun crossover(solution1: Solution, solution2: Solution): Solution {
    return solution1.zip(solution2).map { (gene1, gene2) ->
        val weight = Random.nextDouble() // Unique weight for each gene
        when {
            gene1 is Double && gene2 is Double -> gene1 * weight + gene2 * (1 - weight)
            gene1 is Int && gene2 is Int -> (gene1 * weight + gene2 * (1 - weight)).toInt()
            else -> gene1
        }
    }
}

fun mutation(solution: Solution, parameterTypes: List<Pair<KType, List<Double>>>): Solution {
    val probability = 1.0 / solution.size
    return solution.zip(parameterTypes).map { (element, paramType) ->
        if (Random.nextDouble() < probability) {
            val random = ThreadLocalRandom.current()

            val variance = paramType.second.getOrNull(2) ?: 0.0
            val standardDeviation = Math.sqrt(variance)
            val minValue = paramType.second.getOrNull(0) ?: Double.MIN_VALUE // for clipping gene
            val maxValue = paramType.second.getOrNull(1) ?: Double.MAX_VALUE // for clipping gene

            when (element) {
                is Double -> (element + standardDeviation * random.nextGaussian()).coerceIn(minValue, maxValue)
                is Int -> (element + (standardDeviation * random.nextGaussian()).toInt()).coerceIn(minValue.toInt(), maxValue.toInt())
                else -> element
            }
        } else {
            element
        }
    }
}

fun nonDominatedSort(nsgaInfos: List<NSGAInfo>): List<List<NSGAInfo>> {
    val fronts = mutableListOf<MutableList<NSGAInfo>>()
    fronts.add(mutableListOf())

    val dominatedNsgaInfos = nsgaInfos.map { mutableListOf<NSGAInfo>() }
    val dominationCounts = MutableList(nsgaInfos.size) { 0 }

    for (i in nsgaInfos.indices) {
        for (j in i + 1 until nsgaInfos.size) {
            if (dominates(nsgaInfos[i], nsgaInfos[j])) {
                dominatedNsgaInfos[i].add(nsgaInfos[j])
                dominationCounts[j]++
            } else if (dominates(nsgaInfos[j], nsgaInfos[i])) {
                dominatedNsgaInfos[j].add(nsgaInfos[i])
                dominationCounts[i]++
            }
        }
        if (dominationCounts[i] == 0) {
            nsgaInfos[i].rank = 1
            fronts[0].add(nsgaInfos[i])
        }
    }

    var currentFront = fronts[0]
    var nextFrontIndex = 1
    while (currentFront.isNotEmpty()) {
        val nextFront = mutableListOf<NSGAInfo>()
        for (currentPoint in currentFront) {
            for (dominated in dominatedNsgaInfos[currentPoint.index]) {
                dominationCounts[dominated.index]--
                if (dominationCounts[dominated.index] == 0) {
                    dominated.rank = nextFrontIndex
                    nextFront.add(dominated)
                }
            }
        }
        if (nextFront.isNotEmpty()) {
            fronts.add(nextFront)
            nextFrontIndex++
        }
        currentFront = nextFront
    }

    return fronts
}

fun crowdingDistanceAssignment(front: List<NSGAInfo>) {
    val n = front.size
    front.forEach { it.distance = 0.0 }

    val numberOfObjectives = front[0].objectives.size
    for (i in 0 until numberOfObjectives) {
        front.sortedBy { it.objectives[i] }.apply {
            this.first().distance = Double.POSITIVE_INFINITY
            this.last().distance = Double.POSITIVE_INFINITY
            for (j in 1 until n - 1) {
                this[j].distance += (this[j + 1].objectives[i] - this[j - 1].objectives[i]) / this[0].objectives[i] - this[n - 1].objectives[i]
            }
        }
    }
}

fun dominates(a: NSGAInfo, b: NSGAInfo): Boolean {
    var betterInAtLeastOneObjective = false
    for (i in 0 until a.objectives.size) {
        if (a.objectives[i] < b.objectives[i]) {
            betterInAtLeastOneObjective = true
        } else if (a.objectives[i] > b.objectives[i]) {
            return false
        }
    }
    return betterInAtLeastOneObjective
}

fun rankRunResults(nsgaInfos: List<NSGAInfo>): List<Int> {
    val sortedFronts = nonDominatedSort(nsgaInfos)
    sortedFronts.forEach { front ->
        crowdingDistanceAssignment(front)
    }

    return sortedFronts.flatten().sortedWith(compareBy({ it.rank }, { -it.distance }))
            .map { it.index }
}

fun String.toBoolOrNull(): Boolean? {
    return when (this.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

fun main(args: Array<String>) {
    if (args.size < 5) {
        println("Please provide the number of clones, seconds as a command-line arguments.")
        return
    }
    // clones - number of population in each generation
    val clones = args[0].toIntOrNull()
    if (clones == null || clones <= 0) {
        println("Invalid number of clones. Please provide a positive integer.")
        return
    }
    // seconds - number of seconds for algo
    val seconds = args[1].toIntOrNull()
    if (seconds == null || seconds <= 0) {
        println("Invalid number of seconds. Please provide a positive integer.")
        return
    }
    // params_config - path of parameters config
    val paramsConfigPath = args[2]
    if (paramsConfigPath == null) {
        println("Invalid path.")
        return
    }
    // use_nsga - path of parameters config
    val useNsga = args[3].toBoolOrNull()
    if (useNsga == null) {
        println("Invalid bool.")
        return
    }
    // tournamentSize - number of participants in the tournament for algo
    val tournamentSize = args[4].toIntOrNull()
    if (tournamentSize == null || tournamentSize <= 0) {
        println("Invalid number of participants in tournament. Please provide a positive integer.")
        return
    }
    // crossoverProbability - number of participants in the tournament for algo
    val crossoverProbability = args[5].toDoubleOrNull()
    if (crossoverProbability == null || crossoverProbability <= 0) {
        println("Invalid crossover probablility. Please provide a positive double.")
        return
    }
    // output_file_path - number of participants in the tournament for algo
    val outputFile = args[6]
    if (crossoverProbability == null) {
        println("Invalid outputFilePath.")
        return
    }


    val personTest = PersonTest()

    // Assuming TestPersonGeneric is a member function of PersonTest
    val kFunction = personTest::TestPersonGeneric


    val config = parseConfigJsonFromFile(paramsConfigPath)
    val parameterMetaData = getParameterMetaData(kFunction)
    val parameterInfo = parameterMetaData.map{getParameterInfo(it, config)}

    var generation = generateParameters(parameterInfo, clones)

    var generationNumber = 0
    val endTime = System.currentTimeMillis() + seconds * 1000
    while (System.currentTimeMillis() < endTime) {
        val fitFunctionResults = generation.map { parameterSet ->
            // Invoke the function with the parameter set
            val result = kFunction.call(*parameterSet.toTypedArray())
            val resultArray = result as? DoubleArray
            resultArray
        }


        if (useNsga) {
            var nsgaInfos = fitFunctionResults.zip(0 until fitFunctionResults.size).map { (element, index) ->
                NSGAInfo(element?.toTypedArray() ?: emptyArray<Double>(), index)
            }
            val preiousGenerationIndexes = rankRunResults(nsgaInfos)

            val inverted = MutableList<Double>(preiousGenerationIndexes.size) { 0.0 }

            for (i in preiousGenerationIndexes.indices) {
                inverted[preiousGenerationIndexes[i]] = i.toDouble()
            }

            val childrenGeneration = getNextGenerationSimple(generation, inverted, parameterInfo, tournamentSize, crossoverProbability)
            val childrenGenerationResult = childrenGeneration.map { parameterSet ->
                // Invoke the function with the parameter set
                val result = kFunction.call(*parameterSet.toTypedArray())
                val resultArray = result as? DoubleArray
                resultArray
            }

            val candidatesForNextGeneration = generation + childrenGeneration
            val candidatesForNextGenerationResults = fitFunctionResults + childrenGenerationResult


            nsgaInfos = fitFunctionResults.zip(0 until fitFunctionResults.size).map { (element, index) ->
                NSGAInfo(element?.toTypedArray() ?: emptyArray<Double>(), index)
            }

            val nextGenerationIndexes = rankRunResults(nsgaInfos).take(clones)

            generation = nextGenerationIndexes.map { candidatesForNextGeneration[it] }

        } else {
            val fitFunctionResultsTransformed = fitFunctionResults.map { it?.sum() ?: 0.0 }
            generation = getNextGenerationSimple(generation, fitFunctionResultsTransformed, parameterInfo, tournamentSize, crossoverProbability)
        }
        generationNumber++
    }
    File(outputFile).printWriter().use { out ->
        out.println("Generation number - ${generationNumber}. Generation - ${generation}")
    }
}