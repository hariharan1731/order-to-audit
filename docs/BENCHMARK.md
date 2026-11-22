# Benchmark results and how each number is calculated

All figures are from the official problem statement and are asserted by automated tests
(service tests, web tests and a real-browser test). Nothing was adjusted to make a test pass.
Money is kept in integer paise; stock in base units (grams, millilitres, pieces, packets).

## Inputs
Opening: flour 100 kg @ Rs 42, oil 30 L @ Rs 140, spice 5 kg @ Rs 200, pouches 500 @ Rs 2.50, cartons 30 @ Rs 15,
finished goods 50 packets (no supplied rate, so unvalued). Purchase: flour 60 kg @ Rs 45.
Run: flour 40 kg, oil 10.5 L, spice 1.2 kg, pouches 194 (188 good + 6 damaged), cartons 9, good packets 188.
Overheads: labour Rs 1,200, gas and power Rs 600. Selling price Rs 50 per packet, GST 5%.

## Stock after the run
| Item | Calculation | Result |
|---|---|---|
| Flour | 100 + 60 - 40 kg | **120 kg** |
| Oil | 30 - 10.5 L | **19.5 L** |
| Pouches | 500 - 194 | **306** |
| Finished goods | 50 + 188 | **238 packets** |
| After Retailer A dispatch | 238 - 150 | **88 packets** |

## Variances (strict `>` 3% tolerance)
- Yield: (188 - 200) / 200 = **-6%** -> flagged.
- Oil: (10.5 - 10) / 10 = **+5%** -> flagged.

## Retailer A invoice (Tamil Nadu = intra-state)
150 x Rs 50 = **Rs 7,500** taxable. GST 5% = Rs 375, split **CGST 187.50 + SGST 187.50**. Total **Rs 7,875**.
Payment Rs 4,000, so outstanding = 7,875 - 4,000 = **Rs 3,875**.

## Distributor B
Stock after Retailer A = 88. Order 120 -> shortage 120 - 88 = **32 packets**; approval is blocked until the
quantity is reduced. Karnataka is another state, so an invoice would carry **IGST** (reduced to 88: taxable
Rs 4,400, IGST Rs 220, total Rs 4,620).

## Cost per packet and margin (weighted-average cost)
| Material | Calculation | Rs |
|---|---|---|
| Flour | WAC (100 x 42 + 60 x 45) / 160 = 43.125 per kg; x 40 kg | 1,725.00 |
| Oil | 10.5 L x 140 | 1,470.00 |
| Spice mix | 1.2 kg x 200 | 240.00 |
| Pouches | 194 x 2.50 (damaged ones are real cost) | 485.00 |
| Cartons | 9 x 15 | 135.00 |
| **Materials** | | **4,055.00** |
| Labour + gas/power | 1,200 + 600 | 1,800.00 |
| **Total run cost** | | **5,855.00** |

Cost per packet = 5,855.00 / 188 = **Rs 31.1436** (3114.3617 paise kept internally; shown as **Rs 31.14**).
Margin = (50 - 31.1436) / 50 = **37.71%**.
