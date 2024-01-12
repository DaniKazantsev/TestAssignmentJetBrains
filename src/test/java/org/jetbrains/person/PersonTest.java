package org.jetbrains.person;


import org.jetbrains.car.Car;
import org.jetbrains.car.PetrolCar;
import org.junit.jupiter.api.Test;

public class PersonTest {
    public  double[] TestPersonGeneric(double location, double energyUsageRate, int personAge, double homeLocation, double workLocation, int trips) {
        Car car = new PetrolCar(location, energyUsageRate);
        Person person = new Person(personAge, homeLocation, workLocation, car);
        for (int i = 0; i <= trips; i = i + 2) {
            person.goToWork();
            person.goToHome();
        }
        person.goToWork();

        assert (car.getEnergyValue() > 0 && car.getEnergyValue() <= 100);
        double[] result = new double[2];
        result[0] = Math.min(car.getEnergyValue(), 100 - car.getEnergyValue());
        result[1] = Math.min(car.getLocation(), 100 - car.getLocation());
        return result;
    }
    @Test
    public void TestConcretePersonFail() {
        TestPersonGeneric(10, 2, 19, 10.1, 46.10, 2);
    }
    @Test
    public void TestConcretePersonSuccess() {
        TestPersonGeneric(10.1, 1, 25, 10.2, 46.20, 2);
    }

    @Test
    public void TestConcretePersonMy() {
        TestPersonGeneric(
                28.1673701673378, 1.3510076096827146, 43, 18.003546113709245, 0.42958511905485386, 2
        );
    }
}