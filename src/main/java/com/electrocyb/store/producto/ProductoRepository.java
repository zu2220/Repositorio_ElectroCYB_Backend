package com.electrocyb.store.producto;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

    // Ya lo tenías
    List<Producto> findByCategoriaIgnoreCase(String categoria);

    List<Producto> findTop5ByNombreContainingIgnoreCase(String nombre);

    // NUEVOS:
    List<Producto> findTop5ByDescripcionContainingIgnoreCase(String descripcion);

    List<Producto> findTop5ByCategoriaContainingIgnoreCase(String categoria);
}